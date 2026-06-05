package org.mockserver.blob.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link BlobStore} implementation backed by Azure Blob Storage.
 * Blob keys are mapped to Azure blob names with an optional
 * configurable prefix.
 * <p>
 * Metadata is stored as Azure blob metadata (custom key-value pairs
 * on the blob itself).
 * <p>
 * Thread-safety: {@link BlobServiceClient} is thread-safe; this class
 * adds no mutable state beyond the injected client and configuration.
 */
public class AzureBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(AzureBlobStore.class);

    private final BlobContainerClient containerClient;
    private final String keyPrefix;

    /**
     * Creates an Azure blob store.
     *
     * @param containerClient the Azure container client (caller owns lifecycle)
     * @param keyPrefix       optional key prefix; empty string for no prefix
     */
    public AzureBlobStore(BlobContainerClient containerClient, String keyPrefix) {
        this.containerClient = containerClient;
        this.keyPrefix = keyPrefix != null ? keyPrefix : "";
    }

    private String toAzureName(String key) {
        return keyPrefix + key;
    }

    private String fromAzureName(String azureName) {
        if (azureName.startsWith(keyPrefix)) {
            return azureName.substring(keyPrefix.length());
        }
        return azureName;
    }

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        String azureName = toAzureName(key);
        Map<String, String> encodedMeta = metadata != null ? encodeMetadataKeys(metadata) : Collections.emptyMap();

        var blobClient = containerClient.getBlobClient(azureName);

        // Upload data and metadata atomically via BlobParallelUploadOptions
        // so that a concurrent get() never sees data without its metadata.
        BlobParallelUploadOptions uploadOptions = new BlobParallelUploadOptions(
            new ByteArrayInputStream(data), data.length)
            .setMetadata(encodedMeta.isEmpty() ? null : encodedMeta);
        blobClient.uploadWithResponse(uploadOptions, null, null);

        LOG.debug("put blob '{}' to azure://{}/{} ({} bytes, {} metadata entries)",
            key, containerClient.getBlobContainerName(), azureName, data.length, encodedMeta.size());
    }

    @Override
    public Optional<Blob> get(String key) {
        String azureName = toAzureName(key);
        var blobClient = containerClient.getBlobClient(azureName);

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            byte[] data = outputStream.toByteArray();

            var properties = blobClient.getProperties();
            Map<String, String> encodedMeta = properties.getMetadata();
            Map<String, String> metadata = encodedMeta != null
                ? decodeMetadataKeys(encodedMeta)
                : Collections.emptyMap();

            return Optional.of(new Blob(key, data, metadata));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<String> list(String prefix) {
        String azurePrefix = toAzureName(prefix);

        ListBlobsOptions options = new ListBlobsOptions()
            .setPrefix(azurePrefix)
            .setDetails(new BlobListDetails().setRetrieveMetadata(false));

        return containerClient.listBlobs(options, null).stream()
            .map(BlobItem::getName)
            .map(this::fromAzureName)
            .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String key) {
        String azureName = toAzureName(key);
        var blobClient = containerClient.getBlobClient(azureName);

        try {
            if (!blobClient.exists()) {
                return false;
            }
            blobClient.delete();
            LOG.debug("deleted blob '{}' from azure://{}/{}", key,
                containerClient.getBlobContainerName(), azureName);
            return true;
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Reversible metadata key encoding for Azure compatibility.
     * <p>
     * Azure blob metadata keys must be valid C# identifiers:
     * {@code [a-zA-Z_][a-zA-Z0-9_]*}. Original keys may contain
     * characters like {@code -}, {@code =}, {@code .}, etc.
     * <p>
     * Encoding scheme (prefix-hex escape):
     * <ul>
     *   <li>Literal underscore {@code _} is escaped to {@code _5f}
     *       (its lowercase hex code point)</li>
     *   <li>Any other character outside {@code [a-zA-Z0-9]} is
     *       escaped to {@code _XX} where {@code XX} is the two-digit
     *       lowercase hex of the character's code point</li>
     *   <li>Letters and digits pass through unescaped</li>
     *   <li>If the encoded key starts with a digit, it is prefixed
     *       with {@code _00} (decoded back to empty string on read)</li>
     * </ul>
     * <p>
     * This is fully reversible: {@code decode(encode(k)).equals(k)}
     * for all keys with code points in {@code [0x00, 0xFF]}. Keys
     * with the same characters never collide because the escape is
     * injective (underscore itself is always escaped).
     *
     * @param metadata the original metadata map
     * @return a new map with Azure-safe encoded keys
     */
    static Map<String, String> encodeMetadataKeys(Map<String, String> metadata) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String encoded = encodeKey(entry.getKey());
            result.put(encoded, entry.getValue());
        }
        return result;
    }

    /**
     * Decodes metadata keys that were encoded by {@link #encodeMetadataKeys}.
     *
     * @param metadata the Azure metadata map with encoded keys
     * @return a new map with the original decoded keys
     */
    static Map<String, String> decodeMetadataKeys(Map<String, String> metadata) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String decoded = decodeKey(entry.getKey());
            result.put(decoded, entry.getValue());
        }
        return result;
    }

    /**
     * Encode a single metadata key to an Azure-safe identifier.
     */
    private static String encodeKey(String key) {
        StringBuilder sb = new StringBuilder(key.length() * 2);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                // Escape as _XX (two-digit lowercase hex)
                sb.append('_');
                sb.append(String.format("%02x", (int) c));
            }
        }
        String encoded = sb.toString();
        // Azure keys must start with a letter or underscore. If the
        // encoded result starts with a digit, prefix with _00 (which
        // decodes to an empty character and is stripped on decode).
        if (!encoded.isEmpty() && Character.isDigit(encoded.charAt(0))) {
            encoded = "_00" + encoded;
        }
        return encoded;
    }

    /**
     * Decode a single Azure metadata key back to the original.
     */
    private static String decodeKey(String encoded) {
        // Strip the _00 leading-digit guard prefix if present
        if (encoded.startsWith("_00") && encoded.length() > 3
            && Character.isDigit(encoded.charAt(3))) {
            encoded = encoded.substring(3);
        }
        StringBuilder sb = new StringBuilder(encoded.length());
        int i = 0;
        while (i < encoded.length()) {
            char c = encoded.charAt(i);
            if (c == '_' && i + 2 < encoded.length()) {
                String hex = encoded.substring(i + 1, i + 3);
                try {
                    int codePoint = Integer.parseInt(hex, 16);
                    sb.append((char) codePoint);
                    i += 3;
                } catch (NumberFormatException e) {
                    // Not a valid escape sequence; pass through literally
                    sb.append(c);
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Closes the Azure blob store. The Azure {@link BlobServiceClient}
     * and {@link BlobContainerClient} do not implement
     * {@link AutoCloseable} -- the underlying Netty/Reactor HTTP client
     * is managed by the SDK and does not expose an explicit close. This
     * is a documented no-op.
     */
    @Override
    public void close() {
        // Azure BlobServiceClient / BlobContainerClient do not implement
        // AutoCloseable. The SDK manages the underlying HTTP client
        // lifecycle internally. No explicit resource release is needed.
        LOG.debug("close() called on AzureBlobStore (no-op: Azure SDK manages HTTP client lifecycle)");
    }
}
