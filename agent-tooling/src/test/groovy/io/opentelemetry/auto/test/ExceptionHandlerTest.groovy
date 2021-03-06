/*
 * Copyright The OpenTelemetry Authors
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

package io.opentelemetry.auto.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.opentelemetry.auto.bootstrap.ExceptionLogger
import io.opentelemetry.auto.tooling.bytebuddy.ExceptionHandlers
import io.opentelemetry.auto.util.test.AgentSpecification
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.dynamic.ClassFileLocator
import org.slf4j.LoggerFactory
import spock.lang.Shared

import static io.opentelemetry.auto.tooling.matcher.NameMatchers.namedOneOf
import static net.bytebuddy.matcher.ElementMatchers.isMethod
import static net.bytebuddy.matcher.ElementMatchers.named

class ExceptionHandlerTest extends AgentSpecification {
  @Shared
  ListAppender testAppender = new ListAppender()
  @Shared
  ResettableClassFileTransformer transformer

  def setupSpec() {
    AgentBuilder builder = new AgentBuilder.Default()
      .disableClassFormatChanges()
      .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
      .type(named(getClass().getName() + '$SomeClass'))
      .transform(
        new AgentBuilder.Transformer.ForAdvice()
          .with(new AgentBuilder.LocationStrategy.Simple(ClassFileLocator.ForClassLoader.of(BadAdvice.getClassLoader())))
          .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
          .advice(
            isMethod().and(named("isInstrumented")),
            BadAdvice.getName()))
      .transform(
        new AgentBuilder.Transformer.ForAdvice()
          .with(new AgentBuilder.LocationStrategy.Simple(ClassFileLocator.ForClassLoader.of(BadAdvice.getClassLoader())))
          .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
          .advice(
            isMethod().and(namedOneOf("smallStack", "largeStack")),
            BadAdvice.NoOpAdvice.getName()))

    ByteBuddyAgent.install()
    transformer = builder.installOn(ByteBuddyAgent.getInstrumentation())

    Logger logger = (Logger) LoggerFactory.getLogger(ExceptionLogger)
    testAppender.setContext(logger.getLoggerContext())
    logger.addAppender(testAppender)
    testAppender.start()
  }

  def cleanupSpec() {
    testAppender.stop()
    transformer.reset(ByteBuddyAgent.getInstrumentation(), AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
  }

  def "exception handler invoked"() {
    setup:
    int initLogEvents = testAppender.list.size()
    expect:
    SomeClass.isInstrumented()
    testAppender.list.size() == initLogEvents + 1
    testAppender.list.get(testAppender.list.size() - 1).getLevel() == Level.DEBUG
    // Make sure the log event came from our error handler.
    // If the log message changes in the future, it's fine to just
    // update the test's hardcoded message
    testAppender.list.get(testAppender.list.size() - 1).getMessage().startsWith("Failed to handle exception in instrumentation for")
  }

  def "exception on non-delegating classloader"() {
    setup:
    int initLogEvents = testAppender.list.size()
    URL[] classpath = [SomeClass.getProtectionDomain().getCodeSource().getLocation(),
                       GroovyObject.getProtectionDomain().getCodeSource().getLocation()]
    URLClassLoader loader = new URLClassLoader(classpath, (ClassLoader) null)
    when:
    loader.loadClass(LoggerFactory.getName())
    then:
    thrown ClassNotFoundException

    when:
    Class<?> someClazz = loader.loadClass(SomeClass.getName())
    then:
    someClazz.getClassLoader() == loader
    someClazz.getMethod("isInstrumented").invoke(null)
    testAppender.list.size() == initLogEvents
  }

  def "exception handler sets the correct stack size"() {
    when:
    SomeClass.smallStack()
    SomeClass.largeStack()

    then:
    noExceptionThrown()
  }

  static class SomeClass {
    static boolean isInstrumented() {
      return false
    }

    static void smallStack() {
      // a method with a max stack of 0
    }

    static void largeStack() {
      // a method with a max stack of 6
      long l = 22l
      int i = 3
      double d = 32.2d
      Object o = new Object()
      println "large stack: $l $i $d $o"
    }
  }
}
