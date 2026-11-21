# Camunda 8 Workflow & DMN Deployment Guide

## 1. Khoi dong Docker
docker-compose up -d elasticsearch zeebe operate
- Zeebe Gateway: http://localhost:26500
- Camunda Operate: http://localhost:8081 (demo / demo)

## 2. Deploy len Camunda 8.2
1. workflow/retail_loan_completeness.dmn
2. workflow/retail_loan_process.bpmn

## 3. Kich hoat Backend
Dat bien moi truong CAMUNDA_ENABLED=true khi chay Spring Boot.
