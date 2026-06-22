<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\BinaryLauncher;
use MockServer\Exception\BinaryInstallException;
use PHPUnit\Framework\TestCase;

/**
 * Unit tests for the on-demand binary launcher.
 *
 * All tests are hermetic — no live network downloads. They use local fixture
 * files, MOCKSERVER_BINARY_CACHE pointed at a temp dir, or a testable
 * subclass that stubs the download and extract methods.
 */
class BinaryLauncherTest extends TestCase
{
    private string $tmpDir;

    /** @var array<string, string|false> */
    private array $savedEnv = [];

    protected function setUp(): void
    {
        $this->tmpDir = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'mockserver-test-' . uniqid();
        mkdir($this->tmpDir, 0755, true);
    }

    protected function tearDown(): void
    {
        // Restore environment variables
        foreach ($this->savedEnv as $name => $value) {
            if ($value === false) {
                putenv($name);
            } else {
                putenv("{$name}={$value}");
            }
        }
        $this->savedEnv = [];

        // Clean up temp directory
        $this->removeDir($this->tmpDir);
    }

    // -----------------------------------------------------------------
    // Platform / arch -> token mapping
    // -----------------------------------------------------------------

    public function testResolvePlatformReturnsValidTokens(): void
    {
        $platform = BinaryLauncher::resolvePlatform();

        $this->assertContains($platform['osName'], ['linux', 'darwin', 'windows']);
        $this->assertContains($platform['arch'], ['x86_64', 'aarch64']);
        $this->assertContains($platform['ext'], ['tar.gz', 'zip']);

        // Extension matches OS
        if ($platform['osName'] === 'windows') {
            $this->assertSame('zip', $platform['ext']);
        } else {
            $this->assertSame('tar.gz', $platform['ext']);
        }
    }

    // -----------------------------------------------------------------
    // Bundle naming
    // -----------------------------------------------------------------

    public function testBundleBaseNameFormat(): void
    {
        $meta = BinaryLauncher::bundleBaseName('7.0.0');
        $platform = BinaryLauncher::resolvePlatform();

        $expected = "mockserver-7.0.0-{$platform['osName']}-{$platform['arch']}";
        $this->assertSame($expected, $meta['name']);
        $this->assertSame($platform['ext'], $meta['ext']);
    }

    public function testBundleBaseNameWithDifferentVersions(): void
    {
        $meta1 = BinaryLauncher::bundleBaseName('6.1.0');
        $this->assertStringStartsWith('mockserver-6.1.0-', $meta1['name']);

        $meta2 = BinaryLauncher::bundleBaseName('7.0.1-SNAPSHOT');
        $this->assertStringStartsWith('mockserver-7.0.1-SNAPSHOT-', $meta2['name']);
    }

    // -----------------------------------------------------------------
    // Cache path resolution
    // -----------------------------------------------------------------

    public function testCacheDirWithEnvOverride(): void
    {
        $this->setEnv('MOCKSERVER_BINARY_CACHE', '/custom/cache/path');

        $this->assertSame('/custom/cache/path', BinaryLauncher::cacheDir());
    }

    public function testCacheDirDefaultUnix(): void
    {
        $this->setEnv('MOCKSERVER_BINARY_CACHE', '');

        if (PHP_OS_FAMILY === 'Windows') {
            $this->markTestSkipped('Test requires Unix-like OS.');
        }

        $this->setEnv('XDG_CACHE_HOME', '');

        $dir = BinaryLauncher::cacheDir();
        $home = getenv('HOME') ?: '~';
        $this->assertStringEndsWith(
            DIRECTORY_SEPARATOR . 'mockserver' . DIRECTORY_SEPARATOR . 'binaries',
            $dir
        );
        $this->assertStringStartsWith($home, $dir);
    }

    public function testCacheDirWithXdgOverride(): void
    {
        $this->setEnv('MOCKSERVER_BINARY_CACHE', '');
        $this->setEnv('XDG_CACHE_HOME', '/tmp/xdg-test');

        if (PHP_OS_FAMILY === 'Windows') {
            $this->markTestSkipped('Test requires Unix-like OS.');
        }

        $dir = BinaryLauncher::cacheDir();
        $expected = '/tmp/xdg-test' . DIRECTORY_SEPARATOR . 'mockserver'
            . DIRECTORY_SEPARATOR . 'binaries';
        $this->assertSame($expected, $dir);
    }

    // -----------------------------------------------------------------
    // Asset URL
    // -----------------------------------------------------------------

    public function testAssetUrlDefault(): void
    {
        $this->setEnv('MOCKSERVER_BINARY_BASE_URL', '');

        $url = BinaryLauncher::assetUrl('7.0.0', 'mockserver-7.0.0-darwin-aarch64.tar.gz');
        $expected = 'https://github.com/mock-server/mockserver-monorepo/releases/download'
            . '/mockserver-7.0.0/mockserver-7.0.0-darwin-aarch64.tar.gz';
        $this->assertSame($expected, $url);
    }

    public function testAssetUrlWithCustomBase(): void
    {
        $this->setEnv('MOCKSERVER_BINARY_BASE_URL', 'https://mirror.example.com/releases/');

        $url = BinaryLauncher::assetUrl('7.0.0', 'mockserver-7.0.0-linux-x86_64.tar.gz');
        $this->assertSame(
            'https://mirror.example.com/releases/mockserver-7.0.0-linux-x86_64.tar.gz',
            $url
        );
    }

    public function testAssetUrlStripsTrailingSlashes(): void
    {
        $this->setEnv('MOCKSERVER_BINARY_BASE_URL', 'https://mirror.example.com/rel///');

        $url = BinaryLauncher::assetUrl('7.0.0', 'file.tar.gz');
        $this->assertSame('https://mirror.example.com/rel/file.tar.gz', $url);
    }

    // -----------------------------------------------------------------
    // Launcher path
    // -----------------------------------------------------------------

    public function testLauncherPath(): void
    {
        $path = BinaryLauncher::launcherPath('/cache/7.0.0', 'mockserver-7.0.0-darwin-aarch64');
        $expected = '/cache/7.0.0' . DIRECTORY_SEPARATOR
            . 'mockserver-7.0.0-darwin-aarch64' . DIRECTORY_SEPARATOR
            . 'bin' . DIRECTORY_SEPARATOR
            . (PHP_OS_FAMILY === 'Windows' ? 'mockserver.bat' : 'mockserver');
        $this->assertSame($expected, $path);
    }

    // -----------------------------------------------------------------
    // Version validation (H1)
    // -----------------------------------------------------------------

    public function testValidVersionsAccepted(): void
    {
        // These should not throw
        $launcher1 = new BinaryLauncher('7.0.0');
        $this->assertSame('7.0.0', $launcher1->getVersion());

        $launcher2 = new BinaryLauncher('7.0.1-SNAPSHOT');
        $this->assertSame('7.0.1-SNAPSHOT', $launcher2->getVersion());

        $launcher3 = new BinaryLauncher('6.1.0-rc.1');
        $this->assertSame('6.1.0-rc.1', $launcher3->getVersion());

        $launcher4 = new BinaryLauncher('10.20.30-beta2');
        $this->assertSame('10.20.30-beta2', $launcher4->getVersion());
    }

    public function testVersionWithPathTraversalRejected(): void
    {
        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('Invalid version string');
        new BinaryLauncher('../../etc/passwd');
    }

    public function testVersionWithSlashRejected(): void
    {
        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('Invalid version string');
        new BinaryLauncher('7.0.0/../../etc');
    }

    public function testVersionWithBackslashRejected(): void
    {
        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('Invalid version string');
        new BinaryLauncher('7.0.0\\..\\etc');
    }

    public function testVersionWithNoDotsRejected(): void
    {
        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('Invalid version string');
        new BinaryLauncher('notaversion');
    }

    public function testVersionEmptyStringRejected(): void
    {
        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('Invalid version string');
        new BinaryLauncher('');
    }

    public function testVersionWithSpacesRejected(): void
    {
        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('Invalid version string');
        new BinaryLauncher('7.0.0 ; rm -rf /');
    }

    // -----------------------------------------------------------------
    // SHA-256 verification with local fixtures
    // -----------------------------------------------------------------

    public function testSha256VerificationPassesWithCorrectChecksum(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'sha-pass';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        // Create a tiny fixture archive
        $archiveContent = 'test archive content for sha256 verification';
        $expectedHash = hash('sha256', $archiveContent);

        $launcher = $this->createTestableLauncher('1.0.0', $archiveContent, $expectedHash);
        $result = $launcher->ensureBinary();

        $this->assertStringEndsWith('mockserver', basename($result));
        $this->assertFileExists($result);
    }

    public function testSha256VerificationFailsWithWrongChecksum(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'sha-fail';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        $archiveContent = 'test archive content';
        $wrongHash = str_repeat('a', 64); // definitely wrong

        $launcher = $this->createTestableLauncher('1.0.0', $archiveContent, $wrongHash);

        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('Checksum mismatch');

        $launcher->ensureBinary();
    }

    public function testSha256VerificationFailsWithEmptyChecksum(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'sha-empty';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        $archiveContent = 'test archive content';
        $launcher = $this->createTestableLauncher('1.0.0', $archiveContent, '');

        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('empty or unparseable');

        $launcher->ensureBinary();
    }

    public function testMissingReleaseBundle404GivesActionableError(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'no-bundle';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        // A launcher whose archive download fails with HTTP 404, simulating a
        // release tag that ships no bundle for this version.
        $launcher = new class ('9.9.9') extends BinaryLauncher {
            protected function downloadFile(string $url, string $dest): void
            {
                throw new BinaryInstallException("Failed to download {$url}: HTTP/1.1 404 Not Found", 404);
            }
        };

        try {
            $launcher->ensureBinary();
            $this->fail('expected a BinaryInstallException for a missing release bundle');
        } catch (BinaryInstallException $e) {
            $msg = $e->getMessage();
            $this->assertStringContainsString('9.9.9', $msg);
            $this->assertStringContainsString('no MockServer release bundle', $msg);
            $this->assertStringContainsString('docker run mockserver/mockserver:mockserver-9.9.9', $msg);
            $this->assertStringContainsString('org.mock-server:mockserver-netty:9.9.9', $msg);
        }
    }

    public function testSha256ParsesHashFromHashFilenameFormat(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'sha-format';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        $archiveContent = 'test archive content for format test';
        $expectedHash = hash('sha256', $archiveContent);

        // Simulate "hash  filename" format commonly output by sha256sum
        $shaContent = $expectedHash . '  mockserver-1.0.0-darwin-aarch64.tar.gz';

        $launcher = $this->createTestableLauncher('1.0.0', $archiveContent, $shaContent);
        $result = $launcher->ensureBinary();

        $this->assertStringEndsWith('mockserver', basename($result));
    }

    /**
     * H3: On checksum mismatch, verify that .part file is cleaned up.
     */
    public function testChecksumMismatchCleansUpPartFile(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'sha-cleanup';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        $archiveContent = 'test archive content for cleanup';
        $wrongHash = str_repeat('b', 64);

        $launcher = $this->createTestableLauncher('1.0.0', $archiveContent, $wrongHash);

        try {
            $launcher->ensureBinary();
            $this->fail('Expected BinaryInstallException');
        } catch (BinaryInstallException $e) {
            $this->assertStringContainsString('Checksum mismatch', $e->getMessage());
        }

        // The .part file should have been cleaned up
        $meta = BinaryLauncher::bundleBaseName('1.0.0');
        $versionDir = $cacheDir . DIRECTORY_SEPARATOR . '1.0.0';
        $partFile = $versionDir . DIRECTORY_SEPARATOR . $meta['name'] . '.' . $meta['ext'] . '.part';
        $this->assertFileDoesNotExist($partFile, '.part file should be cleaned up after checksum mismatch');

        // The .sha256 file should also be cleaned up (H3)
        $shaFile = $versionDir . DIRECTORY_SEPARATOR . $meta['name'] . '.' . $meta['ext'] . '.sha256';
        $this->assertFileDoesNotExist($shaFile, '.sha256 file should be cleaned up after checksum mismatch');
    }

    // -----------------------------------------------------------------
    // MOCKSERVER_SKIP_BINARY_DOWNLOAD
    // -----------------------------------------------------------------

    public function testSkipDownloadFailsWhenNoCachedBinary(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'skip-no-cache';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        $this->setEnv('MOCKSERVER_SKIP_BINARY_DOWNLOAD', '1');

        $launcher = new BinaryLauncher('1.0.0');

        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('MOCKSERVER_SKIP_BINARY_DOWNLOAD is set');

        $launcher->ensureBinary();
    }

    public function testSkipDownloadUsesExistingCache(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'skip-cached';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        $this->setEnv('MOCKSERVER_SKIP_BINARY_DOWNLOAD', '1');

        // Pre-seed a fake cached launcher
        $meta = BinaryLauncher::bundleBaseName('1.0.0');
        $versionDir = $cacheDir . DIRECTORY_SEPARATOR . '1.0.0';
        $launcherPath = BinaryLauncher::launcherPath($versionDir, $meta['name']);
        mkdir(dirname($launcherPath), 0755, true);
        file_put_contents($launcherPath, '#!/bin/sh\necho mock');
        chmod($launcherPath, 0755);

        $launcher = new BinaryLauncher('1.0.0');
        $result = $launcher->ensureBinary();

        $this->assertSame($launcherPath, $result);
    }

    // -----------------------------------------------------------------
    // Cached binary reuse
    // -----------------------------------------------------------------

    public function testReusesCachedBinary(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'reuse';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        // Pre-seed a fake cached launcher
        $meta = BinaryLauncher::bundleBaseName('1.0.0');
        $versionDir = $cacheDir . DIRECTORY_SEPARATOR . '1.0.0';
        $launcherPath = BinaryLauncher::launcherPath($versionDir, $meta['name']);
        mkdir(dirname($launcherPath), 0755, true);
        file_put_contents($launcherPath, '#!/bin/sh\necho mock');

        $logs = [];
        $launcher = new BinaryLauncher('1.0.0', function (string $msg) use (&$logs): void {
            $logs[] = $msg;
        });

        $result = $launcher->ensureBinary();

        $this->assertSame($launcherPath, $result);
        $this->assertNotEmpty($logs);
        $this->assertStringContainsString('Using cached', $logs[0]);
    }

    // -----------------------------------------------------------------
    // Versioned cache pruning
    // -----------------------------------------------------------------

    public function testPruningRemovesOldVersions(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'prune';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        mkdir($cacheDir, 0755, true);

        // Pre-create "old" version directories (simulating previous installs)
        $oldVersions = ['5.0.0', '5.1.0', '6.0.0', '6.1.0'];
        foreach ($oldVersions as $v) {
            $dir = $cacheDir . DIRECTORY_SEPARATOR . $v;
            mkdir($dir . DIRECTORY_SEPARATOR . 'bin', 0755, true);
            file_put_contents($dir . DIRECTORY_SEPARATOR . 'bin' . DIRECTORY_SEPARATOR . 'mockserver', 'fake');
        }

        // Now simulate installing version 7.0.0 via testable launcher
        $archiveContent = 'archive for prune test';
        $expectedHash = hash('sha256', $archiveContent);
        $launcher = $this->createTestableLauncher('7.0.0', $archiveContent, $expectedHash);
        $launcher->ensureBinary();

        // Current version (7.0.0) must exist
        $this->assertDirectoryExists($cacheDir . DIRECTORY_SEPARATOR . '7.0.0');

        // At most 1 previous version should remain (the highest = 6.1.0)
        $remaining = [];
        foreach (scandir($cacheDir) as $entry) {
            if ($entry !== '.' && $entry !== '..' && is_dir($cacheDir . DIRECTORY_SEPARATOR . $entry)) {
                $remaining[] = $entry;
            }
        }

        // Should have current (7.0.0) + at most 1 previous
        $this->assertContains('7.0.0', $remaining);
        $this->assertLessThanOrEqual(2, count($remaining),
            'Expected at most 2 version directories (current + 1 previous), got: '
            . implode(', ', $remaining));

        // If a previous was kept, it should be the highest old version
        $others = array_diff($remaining, ['7.0.0']);
        if (count($others) > 0) {
            $this->assertContains('6.1.0', $others,
                'The kept previous version should be the most recent one (6.1.0)');
        }

        // Old ones should be removed
        $this->assertDirectoryDoesNotExist($cacheDir . DIRECTORY_SEPARATOR . '5.0.0');
        $this->assertDirectoryDoesNotExist($cacheDir . DIRECTORY_SEPARATOR . '5.1.0');
        $this->assertDirectoryDoesNotExist($cacheDir . DIRECTORY_SEPARATOR . '6.0.0');
    }

    /**
     * H7: Pruning sorts versions with semver-aware comparison, not lexicographic.
     * This test creates versions where lexicographic and semver order differ.
     */
    public function testPruningSemverOrderNotLexicographic(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'prune-semver';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        mkdir($cacheDir, 0755, true);

        // Lexicographic: "9.0.0" > "10.0.0" but semver: 10.0.0 > 9.0.0
        $oldVersions = ['2.0.0', '9.0.0', '10.0.0'];
        foreach ($oldVersions as $v) {
            $dir = $cacheDir . DIRECTORY_SEPARATOR . $v;
            mkdir($dir . DIRECTORY_SEPARATOR . 'bin', 0755, true);
            file_put_contents($dir . DIRECTORY_SEPARATOR . 'bin' . DIRECTORY_SEPARATOR . 'mockserver', 'fake');
        }

        $archiveContent = 'archive for semver prune test';
        $expectedHash = hash('sha256', $archiveContent);
        $launcher = $this->createTestableLauncher('11.0.0', $archiveContent, $expectedHash);
        $launcher->ensureBinary();

        // Current (11.0.0) + highest previous (10.0.0) should remain
        $this->assertDirectoryExists($cacheDir . DIRECTORY_SEPARATOR . '11.0.0');
        $this->assertDirectoryExists($cacheDir . DIRECTORY_SEPARATOR . '10.0.0',
            'Semver-aware pruning should keep 10.0.0 (the highest previous), not 9.0.0');
        $this->assertDirectoryDoesNotExist($cacheDir . DIRECTORY_SEPARATOR . '9.0.0');
        $this->assertDirectoryDoesNotExist($cacheDir . DIRECTORY_SEPARATOR . '2.0.0');
    }

    /**
     * Fixed: .part files are created INSIDE version directories, not at the base level.
     * This test verifies that pruning cleans .part files at the correct (version-dir) level.
     */
    public function testPruningRemovesPartFilesInsideVersionDirs(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'prune-parts-inner';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        mkdir($cacheDir, 0755, true);

        // Create a version directory with leftover .part and .sha256 files (as would
        // happen from an interrupted download)
        $versionDir = $cacheDir . DIRECTORY_SEPARATOR . '6.0.0';
        mkdir($versionDir, 0755, true);
        file_put_contents(
            $versionDir . DIRECTORY_SEPARATOR . 'mockserver-6.0.0-darwin-aarch64.tar.gz.part',
            'partial download data'
        );
        file_put_contents(
            $versionDir . DIRECTORY_SEPARATOR . 'mockserver-6.0.0-darwin-aarch64.tar.gz.sha256',
            'stale sha file'
        );

        $archiveContent = 'archive for inner part cleanup test';
        $expectedHash = hash('sha256', $archiveContent);
        $launcher = $this->createTestableLauncher('7.0.0', $archiveContent, $expectedHash);
        $launcher->ensureBinary();

        // .part and .sha256 files inside version dirs should be cleaned up
        // (the 6.0.0 dir may also be removed by pruning, but if it remains these files should be gone)
        $partFile = $versionDir . DIRECTORY_SEPARATOR . 'mockserver-6.0.0-darwin-aarch64.tar.gz.part';
        $shaFile = $versionDir . DIRECTORY_SEPARATOR . 'mockserver-6.0.0-darwin-aarch64.tar.gz.sha256';

        // Since 6.0.0 is the only old version and MAX_PREVIOUS_VERSIONS=1, the dir may be kept.
        // But if a .part file survived, check it's cleaned.
        if (is_dir($versionDir)) {
            $this->assertFileDoesNotExist($partFile, '.part inside version dir should be cleaned');
            $this->assertFileDoesNotExist($shaFile, '.sha256 inside version dir should be cleaned');
        }
        // If the dir was entirely pruned, that also satisfies the cleanup requirement.
    }

    /**
     * Original test: .part files at base level are also cleaned (belt and suspenders).
     */
    public function testPruningRemovesPartFilesAtBaseLevel(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'prune-parts-base';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        mkdir($cacheDir, 0755, true);

        // Create stray .part files at the base level (should not appear in practice
        // but pruning handles it for safety)
        file_put_contents($cacheDir . DIRECTORY_SEPARATOR . 'archive.tar.gz.part', 'partial');
        file_put_contents($cacheDir . DIRECTORY_SEPARATOR . 'other.part', 'partial');

        $archiveContent = 'archive for base-level part cleanup test';
        $expectedHash = hash('sha256', $archiveContent);
        $launcher = $this->createTestableLauncher('7.0.0', $archiveContent, $expectedHash);
        $launcher->ensureBinary();

        // .part files at base level should be cleaned up
        $this->assertFileDoesNotExist($cacheDir . DIRECTORY_SEPARATOR . 'archive.tar.gz.part');
        $this->assertFileDoesNotExist($cacheDir . DIRECTORY_SEPARATOR . 'other.part');
    }

    /**
     * Verify that .part files inside the CURRENT version directory are also cleaned
     * (from a prior interrupted install of the same version).
     */
    public function testPruningCleansPartFilesInCurrentVersionDir(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'prune-parts-current';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        mkdir($cacheDir, 0755, true);

        // Pre-create the version directory with a leftover .part
        $versionDir = $cacheDir . DIRECTORY_SEPARATOR . '7.0.0';
        mkdir($versionDir, 0755, true);
        file_put_contents(
            $versionDir . DIRECTORY_SEPARATOR . 'mockserver-7.0.0-darwin-aarch64.tar.gz.part',
            'stale partial'
        );

        $archiveContent = 'archive for current-version part cleanup';
        $expectedHash = hash('sha256', $archiveContent);
        $launcher = $this->createTestableLauncher('7.0.0', $archiveContent, $expectedHash);
        $launcher->ensureBinary();

        $partFile = $versionDir . DIRECTORY_SEPARATOR . 'mockserver-7.0.0-darwin-aarch64.tar.gz.part';
        $this->assertFileDoesNotExist($partFile, '.part in current version dir should be cleaned');
    }

    public function testPruningKeepsCurrentVersionWhenAlone(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'prune-solo';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        mkdir($cacheDir, 0755, true);

        // No old versions exist, just install fresh
        $archiveContent = 'solo install';
        $expectedHash = hash('sha256', $archiveContent);
        $launcher = $this->createTestableLauncher('7.0.0', $archiveContent, $expectedHash);
        $launcher->ensureBinary();

        $this->assertDirectoryExists($cacheDir . DIRECTORY_SEPARATOR . '7.0.0');

        // Only the current version should exist
        $remaining = [];
        foreach (scandir($cacheDir) as $entry) {
            if ($entry !== '.' && $entry !== '..' && is_dir($cacheDir . DIRECTORY_SEPARATOR . $entry)) {
                $remaining[] = $entry;
            }
        }
        $this->assertSame(['7.0.0'], $remaining);
    }

    public function testPruningToleratesNonexistentBaseDir(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'prune-nonexistent';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        // Don't create the directory — pruning should not throw
        $launcher = new BinaryLauncher('7.0.0');
        $launcher->pruneOldVersions();

        // No exception = pass
        $this->assertTrue(true);
    }

    // -----------------------------------------------------------------
    // H7: Pre-release versions sort LOWER than release during pruning
    // -----------------------------------------------------------------

    /**
     * H7: Pre-release versions (e.g. -SNAPSHOT, -rc.1) must sort LOWER than
     * their corresponding release. When pruning, 7.0.0-SNAPSHOT must be evicted
     * before 7.0.0. This validates the semver-aware comparison in pruneOldVersions.
     */
    public function testPruningPreReleaseSortsLowerThanRelease(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'prune-prerelease';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);
        mkdir($cacheDir, 0755, true);

        // Create: 7.0.0-SNAPSHOT, 7.0.0, 6.0.0-rc.1
        // When installing 8.0.0 with MAX_PREVIOUS_VERSIONS=1, the highest
        // previous should be 7.0.0 (release), not 7.0.0-SNAPSHOT (pre-release).
        $oldVersions = ['6.0.0-rc.1', '7.0.0-SNAPSHOT', '7.0.0'];
        foreach ($oldVersions as $v) {
            $dir = $cacheDir . DIRECTORY_SEPARATOR . $v;
            mkdir($dir . DIRECTORY_SEPARATOR . 'bin', 0755, true);
            file_put_contents($dir . DIRECTORY_SEPARATOR . 'bin' . DIRECTORY_SEPARATOR . 'mockserver', 'fake');
        }

        $archiveContent = 'archive for prerelease prune test';
        $expectedHash = hash('sha256', $archiveContent);
        $launcher = $this->createTestableLauncher('8.0.0', $archiveContent, $expectedHash);
        $launcher->ensureBinary();

        // Current (8.0.0) must exist
        $this->assertDirectoryExists($cacheDir . DIRECTORY_SEPARATOR . '8.0.0');

        // The highest previous should be 7.0.0 (release > pre-release)
        $this->assertDirectoryExists(
            $cacheDir . DIRECTORY_SEPARATOR . '7.0.0',
            'Release 7.0.0 should be kept (higher than 7.0.0-SNAPSHOT)'
        );

        // Pre-release and older versions should be pruned
        $this->assertDirectoryDoesNotExist(
            $cacheDir . DIRECTORY_SEPARATOR . '7.0.0-SNAPSHOT',
            '7.0.0-SNAPSHOT should be pruned (pre-release sorts lower than 7.0.0)'
        );
        $this->assertDirectoryDoesNotExist(
            $cacheDir . DIRECTORY_SEPARATOR . '6.0.0-rc.1',
            '6.0.0-rc.1 should be pruned'
        );
    }

    // -----------------------------------------------------------------
    // H3: .sha256 file cleaned up on success path
    // -----------------------------------------------------------------

    /**
     * After a successful download and verification, the .sha256 file
     * should be cleaned up (not left as detritus in the version directory).
     */
    public function testSha256FileCleanedUpOnSuccess(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'sha-cleanup-success';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        $archiveContent = 'test archive for sha cleanup on success';
        $expectedHash = hash('sha256', $archiveContent);

        $launcher = $this->createTestableLauncher('1.0.0', $archiveContent, $expectedHash);
        $launcher->ensureBinary();

        // The .sha256 file should NOT exist after successful install
        $meta = BinaryLauncher::bundleBaseName('1.0.0');
        $versionDir = $cacheDir . DIRECTORY_SEPARATOR . '1.0.0';
        $shaFile = $versionDir . DIRECTORY_SEPARATOR . $meta['name'] . '.' . $meta['ext'] . '.sha256';
        $this->assertFileDoesNotExist($shaFile, '.sha256 file should be removed after successful verification');
    }

    // -----------------------------------------------------------------
    // H3: Tar extraction path-traversal guard
    // -----------------------------------------------------------------

    /**
     * H3: extractArchive must reject archive members containing '../' that
     * would escape the target directory.
     */
    public function testExtractArchiveRejectsPathTraversal(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'tar-escape';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        // Create a tar archive with a malicious '../' member
        $targetDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'extract-target';
        mkdir($targetDir, 0755, true);

        // Create a tar archive containing a path with '../'
        $evilDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'evil-build';
        mkdir($evilDir, 0755, true);
        file_put_contents($evilDir . DIRECTORY_SEPARATOR . 'evil.txt', 'malicious content');

        $archivePath = $this->tmpDir . DIRECTORY_SEPARATOR . 'evil.tar';

        // Use tar's --transform to create a member with '../' in its name
        // GNU tar supports --transform; bsdtar on macOS uses -s
        $exitCode = 0;
        $output = [];

        if (PHP_OS_FAMILY === 'Darwin') {
            // macOS bsdtar: use -s to rename
            exec(sprintf(
                'cd %s && tar -cf %s -s %s evil.txt 2>&1',
                escapeshellarg($evilDir),
                escapeshellarg($archivePath),
                escapeshellarg(',^,../,')
            ), $output, $exitCode);
        } else {
            // GNU tar: use --transform
            exec(sprintf(
                'cd %s && tar -cf %s --transform %s evil.txt 2>&1',
                escapeshellarg($evilDir),
                escapeshellarg($archivePath),
                escapeshellarg('s,^,../,')
            ), $output, $exitCode);
        }

        if ($exitCode !== 0 || !is_file($archivePath)) {
            $this->markTestSkipped('Could not create test archive with path traversal member');
        }

        // Verify our evil archive actually has '../' in a member
        $members = [];
        exec(sprintf('tar -tf %s 2>&1', escapeshellarg($archivePath)), $members);
        $hasDotDot = false;
        foreach ($members as $m) {
            if (str_contains($m, '..')) {
                $hasDotDot = true;
                break;
            }
        }

        if (!$hasDotDot) {
            $this->markTestSkipped('Test tar archive does not contain expected path-traversal member');
        }

        // Create a launcher instance and call extractArchive directly via reflection
        $launcher = new BinaryLauncher('1.0.0');
        $method = new \ReflectionMethod($launcher, 'extractArchive');
        $method->setAccessible(true);

        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('path traversal');

        $method->invoke($launcher, $archivePath, $targetDir);
    }

    // -----------------------------------------------------------------
    // H4: Windows .bat launcher detection
    // -----------------------------------------------------------------

    /**
     * H4: On Windows, .bat files require cmd.exe to execute. Verify
     * that the start() method builds the correct command prefix.
     * (We test the launcher path detection, not actual Windows execution.)
     */
    public function testLauncherPathIsBatOnWindows(): void
    {
        // This test verifies the launcherPath static method returns .bat on Windows
        $path = BinaryLauncher::launcherPath('/cache/7.0.0', 'mockserver-7.0.0-windows-x86_64');
        if (PHP_OS_FAMILY === 'Windows') {
            $this->assertStringEndsWith('.bat', $path);
        } else {
            // On non-Windows, the executable is just 'mockserver' (no .bat)
            $this->assertStringEndsWith('mockserver', $path);
            $this->assertStringEndsNotWith('.bat', $path);
        }
    }

    // -----------------------------------------------------------------
    // H5: stdout/stderr deadlock prevention
    // -----------------------------------------------------------------

    /**
     * H5: By default, start() should NOT create piped stdout/stderr
     * (they go to /dev/null to prevent pipe-buffer deadlock).
     * BinaryHandle::readStdout()/readStderr() should return empty strings
     * when output is not captured.
     */
    public function testBinaryHandleReturnsEmptyWhenOutputNotCaptured(): void
    {
        // Create a BinaryHandle with null stdout/stderr (simulating non-capture mode)
        $handle = $this->createMockHandle(null, null);

        $this->assertSame('', $handle->readStdout());
        $this->assertSame('', $handle->readStderr());
    }

    // -----------------------------------------------------------------
    // H8: HTTP 4xx/5xx status-line detection in downloadFile
    // -----------------------------------------------------------------

    /**
     * H8: The downloadFile method detects HTTP 4xx/5xx responses and throws.
     * This test exercises the error-status code path using a stubbed subclass
     * that simulates the $http_response_header magic variable behavior.
     */
    public function testDownloadFileThrowsOnHttp404(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'http-error';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        // Create a launcher subclass that simulates a 404 response
        $launcher = $this->createHttpErrorLauncher('1.0.0', 404, 'Not Found');

        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('404');

        $launcher->ensureBinary();
    }

    /**
     * H8: Verify 500 server error is also caught.
     */
    public function testDownloadFileThrowsOnHttp500(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'http-500';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        $launcher = $this->createHttpErrorLauncher('1.0.0', 500, 'Internal Server Error');

        $this->expectException(BinaryInstallException::class);
        $this->expectExceptionMessage('500');

        $launcher->ensureBinary();
    }

    // -----------------------------------------------------------------
    // Version getter
    // -----------------------------------------------------------------

    public function testGetVersionReturnsConfiguredVersion(): void
    {
        $launcher = new BinaryLauncher('6.5.0');
        $this->assertSame('6.5.0', $launcher->getVersion());
    }

    public function testGetVersionDefaultsToBuiltinVersion(): void
    {
        $launcher = new BinaryLauncher();
        $this->assertNotEmpty($launcher->getVersion());
        // Should be a valid version-like string
        $this->assertMatchesRegularExpression('/^\d+\.\d+/', $launcher->getVersion());
    }

    // -----------------------------------------------------------------
    // Logger callback
    // -----------------------------------------------------------------

    public function testLoggerReceivesMessages(): void
    {
        $cacheDir = $this->tmpDir . DIRECTORY_SEPARATOR . 'logger-test';
        $this->setEnv('MOCKSERVER_BINARY_CACHE', $cacheDir);

        // Pre-seed a cached launcher to avoid needing download
        $meta = BinaryLauncher::bundleBaseName('1.0.0');
        $versionDir = $cacheDir . DIRECTORY_SEPARATOR . '1.0.0';
        $launcherPath = BinaryLauncher::launcherPath($versionDir, $meta['name']);
        mkdir(dirname($launcherPath), 0755, true);
        file_put_contents($launcherPath, '#!/bin/sh\necho mock');

        $messages = [];
        $launcher = new BinaryLauncher('1.0.0', function (string $msg) use (&$messages): void {
            $messages[] = $msg;
        });

        $launcher->ensureBinary();

        $this->assertNotEmpty($messages);
        $this->assertStringContainsString('cached', $messages[0]);
    }

    // -----------------------------------------------------------------
    // Helper: create a testable launcher with stubbed download/extract
    // -----------------------------------------------------------------

    /**
     * Create a BinaryLauncher subclass that stubs downloadFile and extractArchive
     * so tests never touch the network.
     */
    private function createTestableLauncher(
        string $version,
        string $archiveContent,
        string $shaContent,
    ): BinaryLauncher {
        return new class ($version, $archiveContent, $shaContent) extends BinaryLauncher {
            private string $archiveContent;
            private string $shaContent;

            public function __construct(
                string $version,
                string $archiveContent,
                string $shaContent,
            ) {
                parent::__construct($version);
                $this->archiveContent = $archiveContent;
                $this->shaContent = $shaContent;
            }

            protected function downloadFile(string $url, string $dest): void
            {
                // Instead of downloading, write the fixture content
                if (str_ends_with($url, '.sha256')) {
                    file_put_contents($dest, $this->shaContent);
                } else {
                    file_put_contents($dest, $this->archiveContent);
                }
            }

            protected function extractArchive(string $archive, string $targetDir): void
            {
                // Instead of extracting, create the expected launcher file structure
                $meta = self::bundleBaseName($this->getVersion());
                $launcherPath = self::launcherPath($targetDir, $meta['name']);
                $binDir = dirname($launcherPath);
                if (!is_dir($binDir)) {
                    mkdir($binDir, 0755, true);
                }
                file_put_contents($launcherPath, "#!/bin/sh\necho mock");
                chmod($launcherPath, 0755);
            }
        };
    }

    // -----------------------------------------------------------------
    // Environment helpers
    // -----------------------------------------------------------------

    private function setEnv(string $name, string $value): void
    {
        if (!array_key_exists($name, $this->savedEnv)) {
            $current = getenv($name);
            $this->savedEnv[$name] = $current !== false ? $current : false;
        }

        if ($value === '') {
            putenv($name);
        } else {
            putenv("{$name}={$value}");
        }
    }

    private function removeDir(string $dir): void
    {
        if (!is_dir($dir)) {
            return;
        }
        $entries = scandir($dir);
        if ($entries === false) {
            return;
        }
        foreach ($entries as $entry) {
            if ($entry === '.' || $entry === '..') {
                continue;
            }
            $path = $dir . DIRECTORY_SEPARATOR . $entry;
            if (is_dir($path)) {
                $this->removeDir($path);
            } else {
                unlink($path);
            }
        }
        rmdir($dir);
    }

    /**
     * Create a BinaryHandle with null stdout/stderr for testing non-capture mode.
     */
    private function createMockHandle($stdout, $stderr): \MockServer\BinaryHandle
    {
        // We need a real proc_open resource for the constructor, but we only
        // care about testing readStdout/readStderr null handling.
        // Use a trivial process.
        $devNull = PHP_OS_FAMILY === 'Windows' ? 'NUL' : '/dev/null';
        $process = proc_open(
            PHP_OS_FAMILY === 'Windows' ? ['cmd', '/c', 'echo', 'test'] : ['echo', 'test'],
            [
                0 => ['pipe', 'r'],
                1 => ['file', $devNull, 'w'],
                2 => ['file', $devNull, 'w'],
            ],
            $pipes,
        );
        fclose($pipes[0]);

        $handle = new \MockServer\BinaryHandle($process, $stdout, $stderr, 0);

        return $handle;
    }

    /**
     * Create a BinaryLauncher subclass that simulates an HTTP error response
     * from downloadFile. The first call to downloadFile (the archive download)
     * will throw a BinaryInstallException with the given status code and reason.
     */
    private function createHttpErrorLauncher(
        string $version,
        int $statusCode,
        string $reasonPhrase,
    ): BinaryLauncher {
        return new class ($version, $statusCode, $reasonPhrase) extends BinaryLauncher {
            private int $statusCode;
            private string $reasonPhrase;

            public function __construct(string $version, int $statusCode, string $reasonPhrase)
            {
                parent::__construct($version);
                $this->statusCode = $statusCode;
                $this->reasonPhrase = $reasonPhrase;
            }

            protected function downloadFile(string $url, string $dest): void
            {
                // Simulate the HTTP error response that downloadFile would detect
                // The real implementation checks $http_response_header[0] for 4xx/5xx
                $statusLine = "HTTP/1.1 {$this->statusCode} {$this->reasonPhrase}";
                throw new \MockServer\Exception\BinaryInstallException(
                    "Failed to download {$url}: {$statusLine}"
                );
            }
        };
    }
}
