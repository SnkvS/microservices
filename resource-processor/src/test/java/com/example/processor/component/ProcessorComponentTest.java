package com.example.processor.component;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.*;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/component")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.example.processor.component")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,json:target/component-cucumber.json,html:target/component-cucumber.html")
public class ProcessorComponentTest { }
