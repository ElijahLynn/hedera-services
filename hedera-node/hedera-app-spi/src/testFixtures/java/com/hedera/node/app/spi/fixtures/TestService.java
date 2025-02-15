/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.spi.fixtures;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.Service;
import com.hedera.node.app.spi.fixtures.state.TestSchema;
import com.hedera.node.app.spi.state.Schema;
import com.hedera.node.app.spi.state.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

public class TestService implements Service {
    private final String name;
    private final List<Schema> schemas;

    public TestService(@NonNull final String name) {
        this.name = requireNonNull(name);
        this.schemas = List.of();
    }

    public TestService(@NonNull final String name, @NonNull final List<Schema> schemas) {
        this.name = requireNonNull(name);
        this.schemas = requireNonNull(schemas);
    }

    @NonNull
    @Override
    public String getServiceName() {
        return name;
    }

    @Override
    public void registerSchemas(@NonNull SchemaRegistry registry) {
        schemas.forEach(registry::register);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private final List<Schema> schemas = new ArrayList<>();

        private Builder() {}

        public Builder name(@NonNull final String name) {
            this.name = requireNonNull(name);
            return this;
        }

        public Builder schema(@NonNull final TestSchema.Builder schemaBuilder) {
            this.schemas.add(schemaBuilder.build());
            return this;
        }

        public Builder schema(@NonNull final Schema schema) {
            this.schemas.add(schema);
            return this;
        }

        public TestService build() {
            return new TestService(name, schemas);
        }
    }
}
