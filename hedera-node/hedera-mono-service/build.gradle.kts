/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

plugins {
    id("com.hedera.hashgraph.conventions")
    id("com.hedera.hashgraph.benchmark-conventions")
    id("java-test-fixtures")
}

description = "Hedera Application - MONO Service Implementation"

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    annotationProcessor("dagger.compiler")
    requires("com.hedera.node.app.service.mono")
    requires("awaitility")
    requires("com.github.benmanes.caffeine")
    requires("io.github.classgraph")
    requires("net.i2p.crypto.eddsa")
    requires("org.apache.logging.log4j.core")
    requires("org.apache.logging.log4j.core")
    requires("org.assertj.core")
    requires("org.hamcrest")
    requires("org.hyperledger.besu.plugin.api")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.junitpioneer")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
    requiresStatic("com.github.spotbugs.annotations")
}

jmhModuleInfo {
    annotationProcessor("dagger.compiler")
    requires("com.hedera.node.app.hapi.utils")
    requires("com.hedera.node.app.spi")
    requires("com.hedera.node.hapi")
    requires("com.github.spotbugs.annotations")
    requires("com.google.common")
    requires("com.google.protobuf")
    requires("com.swirlds.common")
    requires("com.swirlds.fcqueue")
    requires("com.swirlds.merkle")
    requires("com.swirlds.merkledb")
    requires("com.swirlds.virtualmap")
    requires("dagger")
    requires("javax.inject")
    requires("jmh.core")
    requires("org.apache.commons.io")
    requires("org.apache.commons.lang3")
    requires("org.hyperledger.besu.datatypes")
    requires("org.hyperledger.besu.evm")
    requires("tuweni.bytes")
    requires("tuweni.units")
}

// Replace variables in semantic-version.properties with build variables
tasks.processResources {
    inputs.property("protoVersion", libs.versions.hapi.proto.get())
    inputs.property("hederaServicesVersion", project.version)
    filesMatching("semantic-version.properties") {
        filter { line: String ->
            if (line.contains("hapi-proto.version")) {
                "hapi.proto.version=${inputs.properties["protoVersion"]}"
            } else if (line.contains("project.version")) {
                "hedera.services.version=${inputs.properties["hederaServicesVersion"]}"
            } else {
                line
            }
        }
    }
}
