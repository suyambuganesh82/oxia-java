/*
 * Copyright © 2022-2024 StreamNative Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.oxia.client.perf.ycsb.generator;

import org.apache.commons.math3.distribution.UniformIntegerDistribution;

final class UniformKeyGenerator implements Generator<String> {
    private final String prefix;
    private final UniformIntegerDistribution distribution;

    public UniformKeyGenerator(KeyGeneratorOptions options) {
        this.prefix = options.prefix();
        this.distribution = new UniformIntegerDistribution(options.lowerBound(), options.upperBound());
    }

    @Override
    public String nextValue() {
        return prefix + distribution.sample();
    }
}