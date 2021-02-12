/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.junit5

import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.openrewrite.*
import org.openrewrite.java.JavaParser
import org.openrewrite.maven.MavenParser

class FindJUnitThenAddJUnitDependenciesTest {

    private val javaParser = JavaParser.fromJavaVersion().classpath(
            JavaParser.dependenciesFromClasspath("junit-jupiter-api", "apiguardian-api")).build()

    private val mavenParser = MavenParser.builder().build()

    @Test
    fun addDependenciesWhenJUnitTestsExist() {
        val javaSource = javaParser.parse("""
                    import org.junit.jupiter.api.Test;                   
                    class MyTest {
                        @Test
                        void test() {
                        }
                    }
                """)[0]

        val mavenSource = mavenParser.parse("""
            <project>
              <groupId>com.mycompany.app</groupId>
              <artifactId>my-app</artifactId>
              <version>1</version>
            </project>
        """.trimIndent())[0]

        val recipe = FindJUnit().doNext(AddJUnitDependencies().apply { setVersion("5.7.1") })

        val results = recipe.run(listOf<SourceFile>(javaSource, mavenSource),
                InMemoryExecutionContext { t: Throwable? -> fail<Any>("Recipe threw an exception", t) },
        )
        assertThat(results).`as`("Recipe must make changes").isNotEmpty
        assertThat(results).hasSize(1)
        val result = results.find { it -> it.before === mavenSource }
        assertThat(result).`as`("Recipe must make changes").isNotNull
        assertThat(result!!.after).isNotNull
        assertThat(result.after!!.printTrimmed())
                .isEqualTo("""
            <project>
              <groupId>com.mycompany.app</groupId>
              <artifactId>my-app</artifactId>
              <version>1</version>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-api</artifactId>
                  <version>5.7.1</version>
                  <scope>test</scope>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-engine</artifactId>
                  <version>5.7.1</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent())
    }
}
