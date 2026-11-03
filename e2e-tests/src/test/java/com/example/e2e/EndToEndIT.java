package com.example.e2e;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.*;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/e2e")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.example.e2e")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,json:target/e2e-cucumber.json,html:target/e2e-cucumber.html")
public class EndToEndIT { }
