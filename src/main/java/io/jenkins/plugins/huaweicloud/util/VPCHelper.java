package io.jenkins.plugins.huaweicloud.util;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.ecs.v2.EcsClient;
import com.huaweicloud.sdk.ecs.v2.model.*;
import com.huaweicloud.sdk.eip.v2.EipClient;
import com.huaweicloud.sdk.eip.v2.model.PublicipShowResp;
import com.huaweicloud.sdk.eip.v2.model.ShowPublicipRequest;
import com.huaweicloud.sdk.eip.v2.model.ShowPublicipResponse;
import io.jenkins.plugins.huaweicloud.ECSTemplate;
import io.jenkins.plugins.huaweicloud.VPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VPCHelper {
    private static final Logger LOGGER = Logger.getLogger(VPCHelper.class.getName());

    public static ServerDetail getInstanceWithRetry(String instanceId, VPC vpc) throws SdkException, InterruptedException {
        for (int i = 0; i < 5; i++) {
            try {
                return getInstance(instanceId, vpc);
            } catch (SdkException e) {
                LOGGER.log(Level.FINE, e.getMessage());
                Thread.sleep(5000);
            }
        }
        return getInstance(instanceId, vpc);
    }

    public static ServerDetail getInstance(String instanceId, VPC vpc) throws SdkException {
        ShowServerRequest request = new ShowServerRequest().withServerId(instanceId);
        EcsClient ecsClient = vpc.getEcsClient();
        ShowServerResponse response = ecsClient.showServer(request);
        return response.getServer();
    }

    public static List<ServerTag> getServerTags(String instanceId, VPC vpc) throws SdkException {
        EcsClient ecsClient = vpc.getEcsClient();
        ShowServerTagsRequest request = new ShowServerTagsRequest();
        request.withServerId(instanceId);
        ShowServerTagsResponse response = ecsClient.showServerTags(request);
        return response.getTags();
    }

    public static void deleteServerTags(String instanceId, List<ServerTag> tags, VPC vpc) throws SdkException {
        EcsClient ecsClient = vpc.getEcsClient();
        BatchDeleteServerTagsRequest request = new BatchDeleteServerTagsRequest();
        request.withServerId(instanceId);
        BatchDeleteServerTagsRequestBody body = new BatchDeleteServerTagsRequestBody();
        body.withTags(tags);
        body.withAction(BatchDeleteServerTagsRequestBody.ActionEnum.fromValue("delete"));
        request.withBody(body);
        ecsClient.batchDeleteServerTags(request);
    }

    public static void createServerTags(String instanceId, List<ServerTag> tags, VPC vpc) throws SdkException {
        EcsClient ecsClient = vpc.getEcsClient();
        BatchCreateServerTagsRequest request = new BatchCreateServerTagsRequest();
        request.withServerId(instanceId);
        BatchCreateServerTagsRequestBody body = new BatchCreateServerTagsRequestBody();
        body.withTags(tags);
        body.withAction(BatchCreateServerTagsRequestBody.ActionEnum.fromValue("create"));
        request.withBody(body);
        ecsClient.batchCreateServerTags(request);
    }

    public static void deleteServer(String instanceId, VPC vpc) throws SdkException {
        EcsClient ecsClient = vpc.getEcsClient();
        DeleteServersRequest request = new DeleteServersRequest();
        DeleteServersRequestBody body = new DeleteServersRequestBody();
        List<ServerId> listBodyServers = new ArrayList<>();
        listBodyServers.add(new ServerId().withId(instanceId));
        body.withServers(listBodyServers).withDeletePublicip(true).withDeleteVolume(true);
        request.withBody(body);
        ecsClient.deleteServers(request);
    }

    public static void stopECSInstance(String instanceId, VPC vpc) throws SdkException {
        EcsClient ecsClient = vpc.getEcsClient();
        BatchStopServersRequest request = new BatchStopServersRequest();
        BatchStopServersRequestBody body = new BatchStopServersRequestBody();
        List<ServerId> listOsStopServers = new ArrayList<>();
        listOsStopServers.add(new ServerId().withId(instanceId));
        BatchStopServersOption osStopBody = new BatchStopServersOption();
        osStopBody.withServers(listOsStopServers)
                .withType(BatchStopServersOption.TypeEnum.fromValue("SOFT"));
        body.withOsStop(osStopBody);
        request.withBody(body);
        ecsClient.batchStopServers(request);
    }

    public static void startEcsInstances(List<String> instances, VPC vpc) throws SdkException {
        EcsClient ecsClient = vpc.getEcsClient();
        List<ServerId> listOsStartServers = new ArrayList<>();
        for (String insID : instances) {
            listOsStartServers.add(new ServerId().withId(insID));
        }
        BatchStartServersRequest request = new BatchStartServersRequest();
        BatchStartServersRequestBody body = new BatchStartServersRequestBody();
        BatchStartServersOption osStartbody = new BatchStartServersOption();
        osStartbody.withServers(listOsStartServers);
        body.withOsStart(osStartbody);
        request.withBody(body);
        ecsClient.batchStartServers(request);
    }

    public static PublicipShowResp getEIPInfo(String eipID, VPC vpc) throws SdkException {
        EipClient eipClient = vpc.getEipClient();
        ShowPublicipRequest request = new ShowPublicipRequest();
        request.withPublicipId(eipID);
        ShowPublicipResponse response = eipClient.showPublicip(request);
        return response.getPublicip();
    }

    public static List<ServerDetail> getAllOfServerList(VPC vpc) {
        List<ServerDetail> instances = new ArrayList<>();
        EcsClient ecsClient = vpc.getEcsClient();
        int offset = 1;
        int limit = 25;
        int currentSize;
        do {
            ListServersDetailsRequest request = new ListServersDetailsRequest();
            request.withLimit(limit).withOffset(offset);
            try {
                ListServersDetailsResponse response = ecsClient.listServersDetails(request);
                if (response.getServers() != null) {
                    currentSize = response.getServers().size();
                    instances.addAll(Objects.requireNonNull(filterDeleteInstance(response.getServers())));
                    offset++;
                } else {
                    break;
                }
            } catch (SdkException e) {
                e.printStackTrace();
                break;
            }
        } while (limit == currentSize);
        return instances;
    }

    public static List<ServerDetail> getAllOfServerByTmp(ECSTemplate template) {
        List<ServerDetail> instances = new ArrayList<>();
        EcsClient ecsClient = template.getParent().getEcsClient();
        int offset = 1;
        int limit = 25;
        int currentSize;
        do {
            ListServersDetailsRequest request = new ListServersDetailsRequest();
            request.withLimit(limit).withOffset(offset).withFlavor(template.getFlavorID()).withName(ECSTemplate.srvNamePrefix);
            try {
                ListServersDetailsResponse response = ecsClient.listServersDetails(request);
                if (response.getServers() != null) {
                    currentSize = response.getServers().size();
                    instances.addAll(filterInstance(response.getServers(), template));
                    offset++;
                } else {
                    break;
                }
            } catch (SdkException e) {
                e.printStackTrace();
                break;
            }
        } while (limit == currentSize);
        return instances;
    }

    private static List<ServerDetail> filterInstance(List<ServerDetail> tplAllInstance, ECSTemplate template) {
        List<ServerDetail> filterInstance = new ArrayList<>();
        for (ServerDetail server : tplAllInstance) {
            if (VPCHelper.isTerminated(server.getStatus())) {
                continue;
            }
            if (!server.getImage().getId().equals(template.getImgID())) {
                continue;
            }
            if (!template.description.equals(server.getDescription())) {
                continue;
            }
            filterInstance.add(server);
        }
        return filterInstance;
    }

    private static List<ServerDetail> filterDeleteInstance(List<ServerDetail> servers) {
        List<ServerDetail> sds = new ArrayList<>();
        for (ServerDetail sd : servers) {
            if (!isTerminated(sd.getStatus())) {
                sds.add(sd);
            }
        }
        return sds;
    }


    public static boolean isTerminated(String state) {
        return "DELETED".equals(state) || "SOFT_DELETED".equals(state);
    }


    public static String genSlaveNamePrefix(String description, String flavorId, String imageID) {
        String nameStr = MD516bitUp(description + flavorId + imageID);
        return ECSTemplate.srvNamePrefix + nameStr;
    }

    public static String MD516bitUp(String readyEncryptStr) {
        try {
            if (readyEncryptStr != null) {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(readyEncryptStr.getBytes(StandardCharsets.UTF_8));
                byte[] b = md.digest();
                StringBuilder su = new StringBuilder();
                for (byte value : b) {
                    String haxHex = Integer.toHexString(value & 0xFF);
                    if (haxHex.length() < 2) {
                        su.append("0");
                    }
                    su.append(haxHex);
                }
                return su.substring(8, 24).toUpperCase();
            } else {
                return "";
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return "";
        }
    }
}
