/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.jensklingenberg.mpapt.common

import de.jensklingenberg.mpapt.extension.AbortAnalysisHandlerExtension
import org.jetbrains.kotlin.base.kapt3.AptMode
import org.jetbrains.kotlin.base.kapt3.KaptFlag
import org.jetbrains.kotlin.base.kapt3.KaptOptions
import org.jetbrains.kotlin.base.kapt3.logString
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.kapt3.base.Kapt
import org.jetbrains.kotlin.kapt3.base.incremental.DeclaredProcType
import org.jetbrains.kotlin.kapt3.base.incremental.IncrementalProcessor
import org.jetbrains.kotlin.kapt3.base.util.KaptLogger
import org.jetbrains.kotlin.kapt3.util.MessageCollectorBackedKaptLogger
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import java.io.File
import javax.annotation.processing.Processor

private val KAPT_OPTIONS = CompilerConfigurationKey.create<KaptOptions.Builder>("Kapt options")


class KaptComponentRegistrar  {

    fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration,annotationProcessors: List<KaptProcessor>) {

        val contentRoots = configuration[CLIConfigurationKeys.CONTENT_ROOTS] ?: emptyList()

        val optionsBuilder = (configuration[KAPT_OPTIONS] ?: KaptOptions.Builder()).apply {
            projectBaseDir = File("/home/jens/Code/2019/MpApt/example/src/jvmMain/kotlin/de/jensklingenberg/")
            compileClasspath.addAll(contentRoots.filterIsInstance<JvmClasspathRoot>().map { it.file })
            javaSourceRoots.addAll(contentRoots.filterIsInstance<JavaSourceRoot>().map { it.file })
            classesOutputDir = classesOutputDir ?: configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY)
            mode = AptMode.STUBS_AND_APT

        }

        val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)
                ?: PrintingMessageCollector(System.err, MessageRenderer.PLAIN_FULL_PATHS, optionsBuilder.flags.contains(KaptFlag.VERBOSE))

        val logger = MessageCollectorBackedKaptLogger(
                optionsBuilder.flags.contains(KaptFlag.VERBOSE),
                optionsBuilder.flags.contains(KaptFlag.INFO_AS_WARNINGS),
                messageCollector
        )

        if (!optionsBuilder.checkOptions(project, logger, configuration)) {
            return
        }


        val options = optionsBuilder.build()

        options.sourcesOutputDir.mkdirs()

        if (options[KaptFlag.VERBOSE]) {
            logger.info(options.logString())
        }

        val processors = annotationProcessors.map { IncrementalProcessor(it, DeclaredProcType.INCREMENTAL_BUT_OTHER_APS_ARE_NOT) }

        AnalysisHandlerExtension.registerExtension(project, AbstractKapt3Extension1(processors, options, logger, configuration))
        // StorageComponentContainerContributor.registerExtension(project, Kapt3ComponentRegistrar.KaptComponentContributor())
    }


    private fun KaptOptions.Builder.checkOptions(project: MockProject, logger: KaptLogger, configuration: CompilerConfiguration): Boolean {
        fun abortAnalysis() = AnalysisHandlerExtension.registerExtension(project, AbortAnalysisHandlerExtension())

        if (classesOutputDir == null) {
            if (configuration.get(JVMConfigurationKeys.OUTPUT_JAR) != null) {
                logger.error("Kapt does not support specifying JAR file outputs. Please specify the classes output directory explicitly.")
                // abortAnalysis()
                classesOutputDir = File("/home/jens/Code/2019/MpApt/example/src/jvmMain/kotlin/de/jensklingenberg/mpapt/build/")
                return true
            } else {
                classesOutputDir = configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY)
                        ?: File("/home/jens/Code/2019/MpApt/example/src/jvmMain/kotlin/de/jensklingenberg/build/")
            }
        }


        val kaptStubsDir = File("/home/jens/Code/2019/MpApt/example/src/jvmMain/kotlin/de/jensklingenberg/mpapt/build/")


        sourcesOutputDir = File("/home/jens/Code/2019/MpApt/example/src/jvmMain/kotlin/de/jensklingenberg/")
        stubsOutputDir = kaptStubsDir//File("/home/jens/Code/2019/MpApt/example/src/jvmMain/kotlin/de/jensklingenberg/mpapt")
        if (sourcesOutputDir == null || classesOutputDir == null || stubsOutputDir == null) {
            if (mode != AptMode.WITH_COMPILATION) {
                val nonExistentOptionName = when {
                    sourcesOutputDir == null -> "Sources output directory"
                    classesOutputDir == null -> "Classes output directory"
                    stubsOutputDir == null -> "Stubs output directory"
                    else -> throw IllegalStateException()
                }
                val moduleName = configuration.get(CommonConfigurationKeys.MODULE_NAME)
                        ?: configuration.get(JVMConfigurationKeys.MODULES).orEmpty().joinToString()

                logger.warn("$nonExistentOptionName is not specified for $moduleName, skipping annotation processing")
                abortAnalysis()
            }
            return false
        }

        if (!Kapt.checkJavacComponentsAccess(logger)) {
            abortAnalysis()
            return false
        }

        return true
    }


    companion object {
        /** This kapt compiler plugin is instantiated by K2JVMCompiler using
         *  a service locator. So we can't just pass parameters to it easily.
         *  Instead we need to use a thread-local global variable to pass
         *  any parameters that change between compilations
         */
        val threadLocalParameters: ThreadLocal<Parameters> = ThreadLocal()
    }

    data class Parameters(
            val processors: List<IncrementalProcessor>,
            val kaptOptions: KaptOptions.Builder
    )
}