/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.health.cmd.handler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.health.cmd.core.config.HealthCmdConfig;
import io.ballerina.health.cmd.core.exception.BallerinaHealthException;
import io.ballerina.health.cmd.core.utils.ErrorMessages;
import io.ballerina.health.cmd.core.utils.HealthCmdConstants;
import io.ballerina.health.cmd.core.utils.HealthCmdUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.wso2.healthcare.codegen.tool.framework.commons.config.ToolConfig;
import org.wso2.healthcare.codegen.tool.framework.commons.core.AbstractToolContext;
import org.wso2.healthcare.codegen.tool.framework.commons.core.TemplateGenerator;
import org.wso2.healthcare.codegen.tool.framework.commons.core.Tool;
import org.wso2.healthcare.codegen.tool.framework.commons.exception.CodeGenException;
import org.wso2.healthcare.codegen.tool.framework.commons.model.JsonConfigType;
import org.wso2.healthcare.codegen.tool.framework.fhir.core.FHIRSpecParser;
import org.wso2.healthcare.codegen.tool.framework.fhir.core.FHIRTool;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Tool execution handler class for EHR service gen tool.
 */
public class EHRServiceGenHandler implements Handler {
    private JsonObject configJson;
    private PrintStream printStream;
    private FHIRTool fhirToolLib;
    private String projectName;
    private String ehrName;
    private String orgName;
    private String[] includedProfiles;
    private String dependentPackage;
    //capability statement file path or url
    private String capabilityStatementPath;
    private String authMethod;


    public EHRServiceGenHandler(PrintStream printStream, String specificationPath) {
        this.init(printStream, specificationPath);
    }

    @Override
    public void init(PrintStream printStream, String specificationPath) {
        this.printStream = printStream;
        try {
            configJson = HealthCmdConfig.getParsedConfigFromStream(HealthCmdUtils.getResourceFile(
                    this.getClass(), HealthCmdConstants.CMD_CONFIG_FILENAME));
        } catch (BallerinaHealthException e) {
            throw new RuntimeException(e);
        }
        fhirToolLib = (FHIRTool) initializeLib(
                HealthCmdConstants.CMD_SUB_FHIR, printStream, configJson, specificationPath);
    }

    @Override
    public void setArgs(Map<String, Object> argsMap) {
        for (String key : argsMap.keySet()) {
            switch (key) {
                case "--project-name" -> this.projectName = (String) argsMap.get(key);
                case "--ehr-name" -> this.ehrName = (String) argsMap.get(key);
                case "--org-name" -> this.orgName = (String) argsMap.get(key);
                case "--included-profile" -> this.includedProfiles = (String[]) argsMap.get(key);
                case "--dependent-package" -> this.dependentPackage = (String) argsMap.get(key);
                case "--capability-statement" -> this.capabilityStatementPath = (String) argsMap.get(key);
                case "--auth-method" -> this.authMethod = (String) argsMap.get(key);
                default -> {
                    printStream.println(ErrorMessages.INVALID_OPTION_PROVIDED + key);
                    HealthCmdUtils.exitError(true);
                }
            }
        }
    }

    @Override
    public boolean execute(String specificationPath, String targetOutputPath) {
        JsonElement toolExecConfigs = null;
        if (configJson != null) {
            toolExecConfigs = configJson.getAsJsonObject("fhir")
                    .getAsJsonObject("tools").getAsJsonObject(HealthCmdConstants.CMD_MODE_TEMPLATE);
        } else {
            printStream.println(ErrorMessages.CONFIG_PARSE_ERROR);
            HealthCmdUtils.exitError(true);
        }

        if (toolExecConfigs != null) {
            JsonObject toolExecConfig = toolExecConfigs.getAsJsonObject();
            processCapabilityStatement();

            try {
                ClassLoader classLoader = this.getClass().getClassLoader();
                String templateToolConfigClassName = "org.wso2.healthcare.fhir.codegen.ballerina.project.tool." +
                        "config.BallerinaProjectToolConfig";
                Class<?> templateToolConfigClazz = classLoader.loadClass(templateToolConfigClassName);
                String templateToolClassName = "org.wso2.healthcare.fhir.codegen.ballerina.project.tool.BallerinaProjectTool";
                Class<?> templateToolClazz = classLoader.loadClass(templateToolClassName);
                ToolConfig templateToolConfigInstance = (ToolConfig) templateToolConfigClazz.getConstructor().newInstance();
                templateToolConfigInstance.setTargetDir(targetOutputPath);
                templateToolConfigInstance.setToolName(HealthCmdConstants.CMD_MODE_TEMPLATE);

                templateToolConfigInstance.configure(new JsonConfigType(
                        toolExecConfig.getAsJsonObject().getAsJsonObject("config")));
                Tool templateTool = (Tool) templateToolClazz.getConstructor().newInstance();
                templateTool.initialize(templateToolConfigInstance);
                templateToolConfigInstance.overrideConfig("project.package.igConfig", populateIGConfig(
                                HealthCmdConstants.CMD_DEFAULT_IG_NAME,
                                orgName,
                                includedProfiles,
                                null
                        )
                );
                if (dependentPackage != null) {
                    JsonElement overrideConfig = new Gson().toJsonTree(dependentPackage);
                    JsonElement nameConfig =
                            new Gson().toJsonTree(dependentPackage.substring(dependentPackage.lastIndexOf('/') + 1));
                    templateToolConfigInstance.overrideConfig("project.package.dependentPackage", overrideConfig);
                    templateToolConfigInstance.overrideConfig("project.package.namePrefix", nameConfig);
                }
                fhirToolLib.getToolImplementations().putIfAbsent(HealthCmdConstants.CMD_MODE_PACKAGE, templateTool);
                TemplateGenerator mainTemplateGenerator = templateTool.execute(fhirToolLib.getToolContext());

                Properties toolProperties = new Properties();
                toolProperties.put("ehrServiceGenProperties", mainTemplateGenerator.getGeneratorProperties());
                ((AbstractToolContext) fhirToolLib.getToolContext()).setCustomToolProperties(toolProperties);

                String prebuiltServiceGenToolConfigClassName = "org.wso2.healthcare.fhir.ballerina.prebuiltservicegen.tool.ServiceGenToolConfig";
                Class<?> prebuiltServiceGenToolConfigClazz = classLoader.loadClass(prebuiltServiceGenToolConfigClassName);
                String prebuiltServiceGenToolClassName = "org.wso2.healthcare.fhir.ballerina.prebuiltservicegen.tool.ServiceGenTool";
                ToolConfig prebuiltServiceGenToolConfigInstance = (ToolConfig) prebuiltServiceGenToolConfigClazz.getConstructor().newInstance();
                prebuiltServiceGenToolConfigInstance.setTargetDir(targetOutputPath);
                prebuiltServiceGenToolConfigInstance.setToolName(HealthCmdConstants.CMD_MODE_EHR_SERVICE_GEN);
                prebuiltServiceGenToolConfigInstance.overrideConfig("servicegen.config.projectName", new Gson().toJsonTree(projectName));
                prebuiltServiceGenToolConfigInstance.overrideConfig("servicegen.config.ehrName", new Gson().toJsonTree(ehrName));
                prebuiltServiceGenToolConfigInstance.overrideConfig("servicegen.config.authMethod", new Gson().toJsonTree(authMethod));

                Class<?> prebuiltServiceGenToolClazz = classLoader.loadClass(prebuiltServiceGenToolClassName);
                Tool ehrGenTool = (Tool) prebuiltServiceGenToolClazz.getConstructor().newInstance();
                ehrGenTool.initialize(prebuiltServiceGenToolConfigInstance);
                ehrGenTool.execute(fhirToolLib.getToolContext());
            } catch (CodeGenException | ClassNotFoundException | InvocationTargetException | InstantiationException |
                     IllegalAccessException | NoSuchMethodException e) {
                //TODO: handle exception
            }
        }
        return false;
    }

    private void processCapabilityStatement() {
        if (StringUtils.isNotEmpty(capabilityStatementPath)) {
            //check whether capability statement is a url or a file path
            if (capabilityStatementPath.startsWith("http")) {
                try {
                    String capabilityStatementContent = IOUtils.toString(
                            new java.net.URL(capabilityStatementPath), StandardCharsets.UTF_8);
                    IBaseResource capabilityStatement = FHIRSpecParser.parseDefinition(capabilityStatementContent);
                    if (capabilityStatement instanceof CapabilityStatement) {
                        ((CapabilityStatement) capabilityStatement).getRest().get(0).getResource().forEach(resource -> {
                            if (resource.getSupportedProfile().size() > 0) {
                                //merge the current incliudedProfiles array with the supported profiles
                                includedProfiles = Arrays.copyOf(includedProfiles, includedProfiles.length +
                                        resource.getSupportedProfile().size());
                            }
                        });
                    }
                } catch (Exception e) {
                    printStream.println("Error occurred while reading capability statement from the given url:" +
                            capabilityStatementPath);
                }
            } else {
                File capabilityStatementFile = new File(capabilityStatementPath);
                if (capabilityStatementFile.exists()) {
                    try {
                        IBaseResource capabilityStatement = FHIRSpecParser.parseDefinition(capabilityStatementFile);
                        if (capabilityStatement instanceof CapabilityStatement) {
                            ((CapabilityStatement) capabilityStatement).getRest().get(0).getResource().forEach(resource -> {
                                if (resource.getSupportedProfile().size() > 0) {
                                    if (includedProfiles == null) {
                                        includedProfiles = new String[0];
                                    }
                                    List<String> includedProfilesList = new ArrayList<>(Arrays.asList(includedProfiles));
                                    //merge the current includedProfiles array with the supported profiles
                                    for (CanonicalType profile : resource.getSupportedProfile()) {
                                        if (!includedProfilesList.contains(profile.getValue())) {
                                            includedProfilesList.add(profile.getValue());
                                        }
                                    }
                                    includedProfiles = includedProfilesList.toArray(new String[0]);
                                }

                            });
                            if (ehrName == null) {
                                String publisher = ((CapabilityStatement) capabilityStatement).getPublisher();
                                if (StringUtils.isNotEmpty(publisher)) {
                                    ehrName = publisher;
                                } else {
                                    printStream.println("Cannot set ehr name from capability statement. " +
                                            "Manually set ehr name using --ehr-name option.");
                                    HealthCmdUtils.exitError(true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        printStream.println("Error occurred while reading capability statement file.");
                    }
                } else {
                    printStream.println("Capability statement file does not exist in the given path.");
                }
            }
        }
    }

    private JsonObject populateIGConfig(String name, String orgName, String[] includedProfiles,
                                        String[] excludedProfiles) {

        JsonObject igConfig = new JsonObject();
        igConfig.addProperty("implementationGuide", name);
        String importStatement = orgName != null ? orgName : HealthCmdConstants.CMD_DEFAULT_ORG_NAME + "/" + name;
        igConfig.addProperty("importStatement", importStatement);
        igConfig.addProperty("enable", true);
        JsonArray includedProfilesArray = new JsonArray();

        if (includedProfiles != null) {
            for (String profile : includedProfiles) {
                includedProfilesArray.add(profile);
            }
        }
        igConfig.add("includedProfiles", includedProfilesArray);
        JsonArray excludedProfilesArray = new JsonArray();
        if (excludedProfiles != null) {
            for (String profile : excludedProfiles) {
                excludedProfilesArray.add(profile);
            }
        }
        igConfig.add("excludedProfiles", excludedProfilesArray);
        return igConfig;
    }
}
