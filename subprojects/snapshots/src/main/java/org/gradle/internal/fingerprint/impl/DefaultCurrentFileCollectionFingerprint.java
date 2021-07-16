/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.fingerprint.impl;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.fingerprint.FingerprintHashingStrategy;
import org.gradle.internal.fingerprint.FingerprintingStrategy;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.SnapshotUtil;

import javax.annotation.Nullable;
import java.util.Map;

public class DefaultCurrentFileCollectionFingerprint implements CurrentFileCollectionFingerprint {

    private final Map<String, FileSystemLocationFingerprint> fingerprints;
    private final FingerprintHashingStrategy hashingStrategy;
    private final String identifier;
    private final FileSystemSnapshot roots;
    private final ImmutableMultimap<String, HashCode> rootHashes;
    private final HashCode strategyConfigurationHash;
    private HashCode hash;

    public static CurrentFileCollectionFingerprint from(FileSystemSnapshot roots, FingerprintingStrategy strategy) {
        return from(roots, strategy, null);
    }

    public static CurrentFileCollectionFingerprint from(FileSystemSnapshot roots, FingerprintingStrategy strategy, @Nullable  FileCollectionFingerprint candidate) {
        if (roots == FileSystemSnapshot.EMPTY) {
            return strategy.getEmptyFingerprint();
        }

        ImmutableMultimap<String, HashCode> rootHashes = SnapshotUtil.getRootHashes(roots);
        Map<String, FileSystemLocationFingerprint> fingerprints;
        if (candidate != null && candidate.getStrategyConfigurationHash().equals(strategy.getConfigurationHash()) && Iterables.elementsEqual(candidate.getRootHashes().entries(), rootHashes.entries())) {
            fingerprints = candidate.getFingerprints();
        } else {
            fingerprints = strategy.collectFingerprints(roots);
        }
        if (fingerprints.isEmpty()) {
            return strategy.getEmptyFingerprint();
        }
        return new DefaultCurrentFileCollectionFingerprint(fingerprints, strategy.getHashingStrategy(), strategy.getIdentifier(), roots, rootHashes, strategy.getConfigurationHash());
    }

    private DefaultCurrentFileCollectionFingerprint(
        Map<String, FileSystemLocationFingerprint> fingerprints,
        FingerprintHashingStrategy hashingStrategy,
        String identifier,
        FileSystemSnapshot roots,
        ImmutableMultimap<String, HashCode> rootHashes,
        HashCode strategyConfigurationHash
    ) {
        this.fingerprints = fingerprints;
        this.hashingStrategy = hashingStrategy;
        this.identifier = identifier;
        this.roots = roots;
        this.rootHashes = rootHashes;
        this.strategyConfigurationHash = strategyConfigurationHash;
    }

    @Override
    public HashCode getHash() {
        if (hash == null) {
            Hasher hasher = Hashing.newHasher();
            hashingStrategy.appendToHasher(hasher, fingerprints.values());
            hash = hasher.hash();
        }
        return hash;
    }

    @Override
    public boolean isEmpty() {
        // We'd have created an EmptyCurrentFileCollectionFingerprint if there were no file fingerprints
        return false;
    }

    @Override
    public Map<String, FileSystemLocationFingerprint> getFingerprints() {
        return fingerprints;
    }

    @Override
    public ImmutableMultimap<String, HashCode> getRootHashes() {
        return rootHashes;
    }

    @Override
    public String getStrategyIdentifier() {
        return identifier;
    }

    @Override
    public FileSystemSnapshot getSnapshot() {
        return roots;
    }

    @Override
    public HashCode getStrategyConfigurationHash() {
        return strategyConfigurationHash;
    }

    @Override
    public String toString() {
        return identifier + fingerprints;
    }
}
