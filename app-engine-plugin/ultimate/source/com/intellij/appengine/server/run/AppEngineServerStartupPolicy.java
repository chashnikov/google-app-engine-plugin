/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

package com.intellij.appengine.server.run;

import com.intellij.appengine.sdk.AppEngineSdk;
import com.intellij.appengine.server.instance.AppEngineServerModel;
import com.intellij.appengine.server.integration.AppEngineServerData;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.javaee.run.configuration.CommonModel;
import com.intellij.javaee.run.configuration.JavaCommandLineStartupPolicy;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.packaging.artifacts.Artifact;

import java.io.File;

/**
 * @author nik
 */
public class AppEngineServerStartupPolicy implements JavaCommandLineStartupPolicy {
  public JavaParameters createCommandLine(CommonModel commonModel) throws ExecutionException {
    final AppEngineServerData data = (AppEngineServerData)commonModel.getApplicationServer().getPersistentData();
    final AppEngineSdk sdk = data.getSdk();
    if (StringUtil.isEmpty(sdk.getSdkHomePath())) {
      throw new ExecutionException("Path to App Engine SDK isn't specified");
    }
    final File toolsApiJarFile = sdk.getToolsApiJarFile();
    if (!toolsApiJarFile.exists()) {
      throw new ExecutionException("'" + sdk.getSdkHomePath() + "' isn't valid App Engine SDK installation: '" + toolsApiJarFile.getAbsolutePath() + "' not found"); 
    }
    final JavaParameters javaParameters = new JavaParameters();
    javaParameters.getClassPath().add(toolsApiJarFile.getAbsolutePath());
    javaParameters.setMainClass("com.google.appengine.tools.development.DevAppServerMain");

    final AppEngineServerModel serverModel = (AppEngineServerModel) commonModel.getServerModel();
    final Artifact artifact = serverModel.getArtifact();
    if (artifact == null) {
      throw new ExecutionException("Artifact isn't specified");
    }

    final ParametersList parameters = javaParameters.getProgramParametersList();
    parameters.addParametersString(serverModel.getServerParameters());
    parameters.replaceOrAppend("-p", "");
    parameters.replaceOrAppend("--port", "");
    parameters.add("-p", String.valueOf(serverModel.getLocalPort()));
    parameters.add("--disable_update_check");

    final String outputPath = artifact.getOutputPath();
    if (outputPath == null || outputPath.length() == 0) {
      throw new ExecutionException("Output path isn't specified for '" + artifact.getName() + "' artifact");
    }
    final String explodedPathParameter = FileUtil.toSystemDependentName(outputPath);
    parameters.add(explodedPathParameter);
    javaParameters.setWorkingDirectory(explodedPathParameter);
    final ParametersList vmParameters = javaParameters.getVMParametersList();
    sdk.patchJavaParametersForDevServer(vmParameters);
    if (SystemInfo.isMac) {
      vmParameters.add("-XstartOnFirstThread");
    }
    return javaParameters;
  }
}
