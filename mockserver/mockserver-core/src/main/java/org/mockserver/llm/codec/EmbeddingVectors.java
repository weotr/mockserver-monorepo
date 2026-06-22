package org.mockserver.llm.codec;

import org.mockserver.model.EmbeddingResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * Shared, deterministic embedding-vector generation used by every provider
 * embedding codec (OpenAI, Gemini, Ollama, Bedrock Titan/Cohere).
 * <p>
 * The vector is either reproducibly derived from the input text + seed +
 * dimensions (when {@link EmbeddingResponse#getDeterministicFromInput()} is
 * true and an input is present) or random, then L2-normalised so it behaves
 * like a real embedding (unit length, cosine-comparable). Provider codecs only
 * differ in the JSON envelope they wrap this vector in.
 */
public final class EmbeddingVectors {

    private EmbeddingVectors() {
    }

    /**
     * Build the embedding vector for an {@link EmbeddingResponse} and input,
     * applying the determinism flag, seed, and default dimensions, then
     * L2-normalising the result.
     *
     * @param embedding         the configured embedding response
     * @param input             the request input text (may be null)
     * @param defaultDimensions provider-specific default when no dimensions are configured
     * @return an L2-normalised vector
     */
    public static double[] build(EmbeddingResponse embedding, String input, int defaultDimensions) {
        int dimensions = embedding.getDimensions() != null ? embedding.getDimensions() : defaultDimensions;
        long seed = embedding.getSeed() != null ? embedding.getSeed() : 0L;
        boolean deterministic = Boolean.TRUE.equals(embedding.getDeterministicFromInput());

        double[] vector;
        if (deterministic && input != null) {
            vector = generateDeterministicVector(input, dimensions, seed);
        } else {
            vector = generateRandomVector(dimensions);
        }
        normalizeL2(vector);
        return vector;
    }

    public static double[] generateDeterministicVector(String input, int dimensions, long seed) {
        try {
            byte[] seedBytes = String.valueOf(seed).getBytes(StandardCharsets.UTF_8);
            byte[] dimensionsBytes = String.valueOf(dimensions).getBytes(StandardCharsets.UTF_8);
            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seedBytes);
            digest.update((byte) ':');
            digest.update(dimensionsBytes);
            digest.update((byte) ':');
            digest.update(inputBytes);
            byte[] hash = digest.digest();

            // First 8 bytes as big-endian long
            ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 8);
            long hashLong = buffer.getLong();

            Random random = new Random(hashLong);
            double[] vector = new double[dimensions];
            for (int i = 0; i < dimensions; i++) {
                vector[i] = random.nextDouble() * 2 - 1;
            }
            return vector;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static double[] generateRandomVector(int dimensions) {
        Random random = new Random();
        double[] vector = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = random.nextDouble() * 2 - 1;
        }
        return vector;
    }

    public static void normalizeL2(double[] vector) {
        double sumOfSquares = 0;
        for (double v : vector) {
            sumOfSquares += v * v;
        }
        double norm = Math.sqrt(sumOfSquares);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }

    /**
     * Approximate the prompt token count from the input length, matching the
     * convention used by the chat codecs (~4 chars per token).
     */
    public static int approximateTokens(String input) {
        return input != null ? Math.max(1, input.length() / 4) : 0;
    }
}
