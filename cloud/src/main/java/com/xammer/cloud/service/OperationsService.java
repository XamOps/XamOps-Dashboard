package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;

@Service
public class OperationsService {

    private static final Logger logger = LoggerFactory.getLogger(OperationsService.class);
    private final Ec2Client ec2Client;
    private final RdsClient rdsClient;

    public OperationsService(Ec2Client ec2Client, RdsClient rdsClient) {
        this.ec2Client = ec2Client;
        this.rdsClient = rdsClient;
    }

    public void deleteEbsVolume(String volumeId) {
        logger.info("Deleting EBS volume: {}", volumeId);
        try {
            ec2Client.deleteVolume(req -> req.volumeId(volumeId));
        } catch (Exception e) {
            logger.error("Failed to delete EBS volume: {}", volumeId, e);
        }
    }

    public void stopEc2Instance(String instanceId) {
        logger.info("Stopping EC2 instance: {}", instanceId);
        try {
            ec2Client.stopInstances(req -> req.instanceIds(instanceId));
        } catch (Exception e) {
            logger.error("Failed to stop EC2 instance: {}", instanceId, e);
        }
    }

    public void startEc2Instance(String instanceId) {
        logger.info("Starting EC2 instance: {}", instanceId);
        try {
            ec2Client.startInstances(req -> req.instanceIds(instanceId));
        } catch (Exception e) {
            logger.error("Failed to start EC2 instance: {}", instanceId, e);
        }
    }

    public void stopRdsInstance(String instanceId) {
        logger.info("Stopping RDS instance: {}", instanceId);
        try {
            rdsClient.stopDBInstance(req -> req.dbInstanceIdentifier(instanceId));
        } catch (Exception e) {
            logger.error("Failed to stop RDS instance: {}", instanceId, e);
        }
    }

    public void startRdsInstance(String instanceId) {
        logger.info("Starting RDS instance: {}", instanceId);
        try {
            rdsClient.startDBInstance(req -> req.dbInstanceIdentifier(instanceId));
        } catch (Exception e) {
            logger.error("Failed to start RDS instance: {}", instanceId, e);
        }
    }
}
