/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.common.serialization;

import org.apache.flink.api.common.typeinfo.TypeInfoFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.apache.flink.configuration.PipelineOptions.SERIALIZATION_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SerializerConfigImplTest {
    @Test
    void testReadingDefaultConfig() {
        SerializerConfig config = new SerializerConfigImpl();
        Configuration configuration = new Configuration();

        // mutate config according to configuration
        config.configure(configuration, SerializerConfigImplTest.class.getClassLoader());

        assertThat(config).isEqualTo(new SerializerConfigImpl());
    }

    @Test
    void testDoubleTypeRegistration() {
        SerializerConfigImpl config = new SerializerConfigImpl();
        List<Class<?>> types = Arrays.asList(Double.class, Integer.class, Double.class);
        List<Class<?>> expectedTypes = Arrays.asList(Double.class, Integer.class);

        for (Class<?> tpe : types) {
            config.registerKryoType(tpe);
        }

        int counter = 0;

        for (Class<?> tpe : config.getRegisteredKryoTypes()) {
            assertThat(tpe).isEqualTo(expectedTypes.get(counter++));
        }

        assertThat(expectedTypes).hasSize(counter);
    }

    @Test
    void testNotOverridingRegisteredKryoTypesWithDefaultsFromConfiguration() {
        SerializerConfigImpl config = new SerializerConfigImpl();
        config.registerKryoType(SerializerConfigImplTest.class);
        config.registerKryoType(SerializerConfigImplTest.TestSerializer1.class);

        Configuration configuration = new Configuration();

        // mutate config according to configuration
        config.configure(configuration, Thread.currentThread().getContextClassLoader());

        LinkedHashSet<Object> set = new LinkedHashSet<>();
        set.add(SerializerConfigImplTest.class);
        set.add(SerializerConfigImplTest.TestSerializer1.class);
        assertThat(config.getRegisteredKryoTypes()).isEqualTo(set);
    }

    @Test
    void testNotOverridingRegisteredPojoTypesWithDefaultsFromConfiguration() {
        SerializerConfigImpl config = new SerializerConfigImpl();
        config.registerPojoType(SerializerConfigImplTest.class);
        config.registerPojoType(SerializerConfigImplTest.TestSerializer1.class);

        Configuration configuration = new Configuration();

        // mutate config according to configuration
        config.configure(configuration, Thread.currentThread().getContextClassLoader());

        LinkedHashSet<Object> set = new LinkedHashSet<>();
        set.add(SerializerConfigImplTest.class);
        set.add(SerializerConfigImplTest.TestSerializer1.class);
        assertThat(config.getRegisteredPojoTypes()).isEqualTo(set);
    }

    @Test
    void testNotOverridingDefaultKryoSerializersFromConfiguration() {
        SerializerConfigImpl config = new SerializerConfigImpl();
        config.addDefaultKryoSerializer(
                SerializerConfigImplTest.class, SerializerConfigImplTest.TestSerializer1.class);
        config.addDefaultKryoSerializer(
                SerializerConfigImplTest.TestSerializer1.class,
                SerializerConfigImplTest.TestSerializer2.class);

        Configuration configuration = new Configuration();

        // mutate config according to configuration
        config.configure(configuration, Thread.currentThread().getContextClassLoader());

        LinkedHashMap<Class<?>, Class<? extends Serializer>> serializers = new LinkedHashMap<>();
        serializers.put(
                SerializerConfigImplTest.class, SerializerConfigImplTest.TestSerializer1.class);
        serializers.put(
                SerializerConfigImplTest.TestSerializer1.class,
                SerializerConfigImplTest.TestSerializer2.class);
        assertThat(config.getDefaultKryoSerializerClasses()).isEqualTo(serializers);
    }

    @Test
    void testLoadingPojoTypesFromSerializationConfig() {
        String serializationConfigStr =
                "[org.apache.flink.api.common.serialization.SerializerConfigImplTest:"
                        + " {type: pojo},"
                        + " org.apache.flink.api.common.serialization.SerializerConfigImplTest$TestSerializer1:"
                        + " {type: pojo},"
                        + " org.apache.flink.api.common.serialization.SerializerConfigImplTest$TestSerializer2:"
                        + " {type: pojo}]";
        SerializerConfig serializerConfig = getConfiguredSerializerConfig(serializationConfigStr);

        assertThat(serializerConfig.getRegisteredPojoTypes())
                .containsExactly(
                        SerializerConfigImplTest.class,
                        TestSerializer1.class,
                        TestSerializer2.class);
    }

    @Test
    void testLoadingKryoTypesFromSerializationConfig() {
        String serializationConfigStr =
                "{org.apache.flink.api.common.serialization.SerializerConfigImplTest:"
                        + " {type: kryo},"
                        + " org.apache.flink.api.common.serialization.SerializerConfigImplTest$TestSerializer1:"
                        + " {type: kryo, kryo-type: default, class: org.apache.flink.api.common.serialization.SerializerConfigImplTest$TestSerializer2},"
                        + " org.apache.flink.api.common.serialization.SerializerConfigImplTest$TestSerializer2:"
                        + " {type: kryo, kryo-type: registered, class: org.apache.flink.api.common.serialization.SerializerConfigImplTest$TestSerializer3}}";
        SerializerConfig serializerConfig = getConfiguredSerializerConfig(serializationConfigStr);

        assertThat(serializerConfig.getRegisteredKryoTypes())
                .containsExactly(SerializerConfigImplTest.class);
        assertThat(serializerConfig.getDefaultKryoSerializerClasses())
                .containsExactly(
                        new AbstractMap.SimpleEntry<>(
                                TestSerializer1.class, TestSerializer2.class));
        assertThat(serializerConfig.getRegisteredTypesWithKryoSerializerClasses())
                .containsExactly(
                        new AbstractMap.SimpleEntry<>(
                                TestSerializer2.class, TestSerializer3.class));
    }

    @Test
    void testLoadingTypeInfoFactoriesFromSerializationConfig() {
        String serializationConfigStr =
                "{org.apache.flink.api.common.serialization.SerializerConfigImplTest:"
                        + " {type: typeinfo, class: org.apache.flink.api.common.serialization.SerializerConfigImplTest$TestTypeInfoFactory}}";
        SerializerConfig serializerConfig = getConfiguredSerializerConfig(serializationConfigStr);

        assertThat(serializerConfig.getRegisteredTypeInfoFactories())
                .containsExactly(
                        new AbstractMap.SimpleEntry<>(
                                SerializerConfigImplTest.class, TestTypeInfoFactory.class));
    }

    @Test
    void testLoadingIllegalSerializationConfig() {
        String duplicateClassConfigStr =
                "[org.apache.flink.api.common.serialization.SerializerConfigImplTest:"
                        + " {type: pojo},"
                        + " org.apache.flink.api.common.serialization.SerializerConfigImplTest:"
                        + " {type: pojo}]";
        assertThatThrownBy(() -> getConfiguredSerializerConfig(duplicateClassConfigStr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Duplicated serializer for the same class.");

        String nullTypeConfigStr =
                "{org.apache.flink.api.common.serialization.SerializerConfigImplTest:"
                        + " {class: org.apache.flink.api.common.serialization.SerializerConfigImplTest$TestTypeInfoFactory}}";
        assertThatThrownBy(() -> getConfiguredSerializerConfig(nullTypeConfigStr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage(
                        "Serializer type not specified for"
                                + " class org.apache.flink.api.common.serialization.SerializerConfigImplTest");

        String unsupportedTypeConfigStr =
                "{org.apache.flink.api.common.serialization.SerializerConfigImplTest:"
                        + " {type: random}}";
        assertThatThrownBy(() -> getConfiguredSerializerConfig(unsupportedTypeConfigStr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage(
                        "Unsupported serializer type random for"
                                + " class org.apache.flink.api.common.serialization.SerializerConfigImplTest");
    }

    @Test
    void testCopyDefaultSerializationConfig() {
        SerializerConfig config = new SerializerConfigImpl();
        Configuration configuration = new Configuration();
        config.configure(configuration, SerializerConfigImplTest.class.getClassLoader());

        assertThat(config.copy()).isEqualTo(config);
    }

    @Test
    void testCopySerializerConfig() {
        SerializerConfigImpl serializerConfig = new SerializerConfigImpl();
        Configuration configuration = new Configuration();

        serializerConfig.configure(configuration, SerializerConfigImplTest.class.getClassLoader());
        serializerConfig
                .getDefaultKryoSerializerClasses()
                .forEach(serializerConfig::registerTypeWithKryoSerializer);

        assertThat(serializerConfig.copy()).isEqualTo(serializerConfig);
    }

    private SerializerConfig getConfiguredSerializerConfig(String serializationConfigStr) {
        Configuration configuration = new Configuration();
        configuration.setString(SERIALIZATION_CONFIG.key(), serializationConfigStr);

        SerializerConfig serializerConfig = new SerializerConfigImpl();
        serializerConfig.configure(configuration, Thread.currentThread().getContextClassLoader());
        return serializerConfig;
    }

    private static class TestSerializer1 extends Serializer<SerializerConfigImplTest>
            implements Serializable {
        @Override
        public void write(Kryo kryo, Output output, SerializerConfigImplTest object) {}

        @Override
        public SerializerConfigImplTest read(
                Kryo kryo, Input input, Class<? extends SerializerConfigImplTest> type) {
            return null;
        }
    }

    private static class TestSerializer2
            extends Serializer<SerializerConfigImplTest.TestSerializer1> implements Serializable {
        @Override
        public void write(
                Kryo kryo, Output output, SerializerConfigImplTest.TestSerializer1 object) {}

        @Override
        public SerializerConfigImplTest.TestSerializer1 read(
                Kryo kryo,
                Input input,
                Class<? extends SerializerConfigImplTest.TestSerializer1> type) {
            return null;
        }
    }

    private static class TestSerializer3
            extends Serializer<SerializerConfigImplTest.TestSerializer2> implements Serializable {
        @Override
        public void write(
                Kryo kryo, Output output, SerializerConfigImplTest.TestSerializer2 object) {}

        @Override
        public SerializerConfigImplTest.TestSerializer2 read(
                Kryo kryo,
                Input input,
                Class<? extends SerializerConfigImplTest.TestSerializer2> type) {
            return null;
        }
    }

    private static class TestTypeInfoFactory extends TypeInfoFactory<SerializerConfigImplTest> {
        @Override
        public TypeInformation<SerializerConfigImplTest> createTypeInfo(
                Type t, Map<String, TypeInformation<?>> genericParameters) {
            return null;
        }
    }
}
