package com.example.retail_loan_document_completeness_checker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class ConfirmRecoveryScheduling {}
