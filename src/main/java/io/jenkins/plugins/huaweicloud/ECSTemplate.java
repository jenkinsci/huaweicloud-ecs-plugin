package io.jenkins.plugins.huaweicloud;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.ecs.v2.EcsClient;
import com.huaweicloud.sdk.ecs.v2.model.*;
import com.huaweicloud.sdk.ims.v2.ImsClient;
import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.*;
import hudson.model.labels.LabelAtom;
import hudson.security.Permission;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.huaweicloud.util.ECSAgentConfig;
import io.jenkins.plugins.huaweicloud.util.ECSAgentFactory;
import io.jenkins.plugins.huaweicloud.util.VPCHelper;
import jenkins.model.Jenkins;
import jenkins.slaves.iterators.api.NodeIterator;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ECSTemplate implements Describable<ECSTemplate> {

    private final static Logger LOGGER = Logger.getLogger(ECSTemplate.class.getName());
    public final String description;
    protected transient VPC parent;
    public String vpcid;
    private final String flavorID;
    private final String zone;
    public final String labels;
    public Node.Mode mode;
    private final String subnetIDs;
    public VolumeType rootVolumeType;
    public String rvSizeStr;
    public int rvSize;
    private final List<ECSTag> tags;
    public final String numExecutors;
    public final String remoteFS;
    public final String remoteAdmin;
    public final String idleTerminationMinutes;
    public final String offlineTimeout;
    public final String initScript;
    public final String tmpDir;
    public int launchTimeout;
    public ImageTypeData imgType;
    private final boolean associateEIP;
    public static final String srvNamePrefix = "JenkinsHWCSlave_";
    private /* lazily initialized */ DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties;
    private transient/* almost final */ Set<LabelAtom> labelSet;
    private final int minimumNumberOfInstances;
    public final boolean stopOnTerminate;
    private final String userData;
    public String currentSubnetId;
    public int instanceCap;
    private final boolean mountDV;
    private final String dvSize;
    public VolumeType dvType;
    public String mountQuantity;

    @DataBoundConstructor
    public ECSTemplate(String description, String flavorID,
                       String zone, String labelString, Node.Mode mode, String remoteAdmin,
                       String subnetIDs, VolumeType rootVolumeType, VolumeType dvType, String remoteFS,
                       String rvSizeStr, List<ECSTag> tags, String numExecutors,
                       String idleTerminationMinutes, String offlineTimeout, String launchTimeoutStr, String initScript, String tmpDir,
                       List<? extends NodeProperty<?>> nodeProperties, int minimumNumberOfInstances, boolean associateEIP,
                       boolean stopOnTerminate, String userData, String instanceCapStr, boolean mountDV, String dvSize,
                       String mountQuantity, ImageTypeData imgType) {
        if (StringUtils.isNotBlank(remoteAdmin) || StringUtils.isNotBlank(tmpDir)) {
            LOGGER.log(Level.FINE, "As remoteAdmin or tmpDir is not blank, we must ensure the user has ADMINISTER rights.");
            // Can be null during tests
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null)
                j.checkPermission(Jenkins.ADMINISTER);
        }
        this.mountDV = mountDV;
        this.dvSize = dvSize;
        this.associateEIP = associateEIP;
        this.description = description;
        this.flavorID = flavorID;
        this.zone = zone;
        this.labels = Util.fixNull(labelString);
        this.mode = mode != null ? mode : Node.Mode.NORMAL;
        this.subnetIDs = subnetIDs;
        this.rootVolumeType = rootVolumeType != null ? rootVolumeType : VolumeType.SATA;
        this.dvType = dvType != null ? dvType : VolumeType.SATA;
        this.numExecutors = Util.fixNull(numExecutors).trim();
        this.rvSizeStr = rvSizeStr;
        this.remoteFS = remoteFS;
        this.remoteAdmin = remoteAdmin;
        this.initScript = initScript;
        this.tmpDir = tmpDir;

        try {
            this.rvSize = Integer.parseInt(rvSizeStr);
        } catch (NumberFormatException e) {
            LOGGER.log(Level.FINE, e.getMessage());
        }
        this.tags = tags;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.offlineTimeout = offlineTimeout;
        try {
            this.launchTimeout = Integer.parseInt(launchTimeoutStr);
        } catch (NumberFormatException nfe) {
            this.launchTimeout = Integer.MAX_VALUE;
        }

        try {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        } catch (NumberFormatException e) {
            this.instanceCap = Integer.MAX_VALUE;
        }


        this.nodeProperties = new DescribableList<>(Saveable.NOOP, Util.fixNull(nodeProperties));
        this.minimumNumberOfInstances = minimumNumberOfInstances;
        this.stopOnTerminate = stopOnTerminate;
        this.userData = StringUtils.trimToEmpty(userData);
        this.mountQuantity = mountQuantity;
        this.imgType = imgType;
        readResolve();
    }

    public ImageTypeData getImgType() {
        return imgType;
    }

    public void setImgType(ImageTypeData imgType) {
        this.imgType = imgType;
    }

    public boolean isMountDV() {
        return mountDV;
    }

    public String getMountQuantity() {
        return mountQuantity;
    }

    public int mountNum() {
        try {
            return Integer.parseInt(getMountQuantity());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 1;
    }

    public String getDvSize() {
        return dvSize;
    }

    public int volumeSize() {
        try {
            return Integer.parseInt(getDvSize());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 40;
    }

    public boolean getAssociateEIP() {
        return associateEIP;
    }

    public VPC getParent() {
        if (parent == null) {
            for (Cloud c : Jenkins.get().clouds) {
                if (c instanceof VPC) {
                    VPC cc = (VPC) c;
                    if (cc.getVpcID().equals(this.vpcid)) {
                        parent = cc;
                        break;
                    }
                }
            }
        }
        return parent;
    }

    public String getImgID() {
        String imgID = "";
        if (imgType.isDynamicType()) {
            try {
                ImsClient imsClient = getParent().getImsClient();
                String tag = ((DynamicImageData) imgType).getImgTag();
                imgID = VPCHelper.getImageIDByTag(imsClient, tag);
            } catch (Exception e) {
                LOGGER.info(e.getMessage());
            }
        } else {
            imgID = ((StaticImageData) imgType).getImageId();
        }
        return imgID;
    }

    public int getMinimumNumberOfInstances() {
        return minimumNumberOfInstances;
    }

    public String getSubnetIDs() {
        return subnetIDs;
    }

    public List<String> chooseSubnetID() {
        List<String> subnetIdList = new ArrayList<>();
        if (StringUtils.isBlank(subnetIDs)) {
            return subnetIdList;
        }
        String[] subnets = getSubnetIDs().split(" ");
        subnetIdList.addAll(Arrays.asList(subnets));
        return subnetIdList;
    }

    public String getLabelString() {
        return labels;
    }

    public Node.Mode getMode() {
        return mode;
    }

    @DataBoundSetter
    public void setMode(Node.Mode mode) {
        this.mode = mode;
    }

    public String getSlaveName(String instanceId) {
        return String.format("%s (%s)", getDisplayName(), instanceId);
    }

    public List<ECSTag> getTags() {
        if (null == tags)
            return null;
        return Collections.unmodifiableList(tags);
    }

    public VolumeType getRootVolumeTypeAttribute() {
        return rootVolumeType;
    }

    public VolumeType getDvType() {
        return dvType;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public String getInstanceCapStr() {
        if (instanceCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(instanceCap);
        }
    }

    public String getFlavorID() {
        return flavorID;
    }

    public String getZone() {
        return zone;
    }

    public String getDisplayName() {
        return String.format("ECS(%s) - %s", parent.getDisplayName(), description);
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public String getIdleTerminationMinutes() {
        return idleTerminationMinutes;
    }

    public int getLaunchTimeout() {
        return launchTimeout <= 0 ? Integer.MAX_VALUE : launchTimeout;
    }

    public String getLaunchTimeoutStr() {
        if (launchTimeout == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(launchTimeout);
        }
    }

    public DescribableList<NodeProperty<?>, NodePropertyDescriptor> getNodeProperties() {
        return Objects.requireNonNull(nodeProperties);
    }

    public enum ProvisionOptions {ALLOW_CREATE, FORCE_CREATE}

    public List<ECSAbstractSlave> provision(int number, EnumSet<ProvisionOptions> provisionOptions) throws SdkException, IOException {
        return provisionOnDemand(number, provisionOptions);
    }

    private List<ECSAbstractSlave> provisionOnDemand(int number, EnumSet<ProvisionOptions> provisionOptions) throws SdkException, IOException {
        List<ServerDetail> orphans = findOrphansOrStopInstance(number);
        if (orphans.isEmpty() && !provisionOptions.contains(ProvisionOptions.FORCE_CREATE) &&
                !provisionOptions.contains(ProvisionOptions.ALLOW_CREATE)) {
            logProvisionInfo("No existing instance found - but cannot create new instance");
            return null;
        }
        if (wakeUpInstance(orphans) && orphans.size() == number) {
            return toSlaves(orphans);
        }
        int needCreateCount = number - orphans.size();
        //create ecs instances by huawei ecs API and get the  create instances job id.
        String jobID = createNewInstances(needCreateCount);
        List<ServerDetail> instances = getInstancesByStatus(jobID);
        instances.addAll(orphans);
        return toSlaves(instances);
    }

    private boolean wakeUpInstance(List<ServerDetail> details) {
        boolean result = true;
        List<String> instances = new ArrayList<>();
        for (ServerDetail sd : details) {
            instances.add(sd.getId());
        }
        try {
            if (!instances.isEmpty()) {
                VPCHelper.startEcsInstances(instances, getParent());
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage());
            result = false;
        }
        return result;
    }


    private List<ServerDetail> getInstancesByStatus(String jobID) throws SdkException {
        List<String> serverIds = new ArrayList<>();
        EcsClient ecsClient = parent.getEcsClient();
        ShowJobRequest request = new ShowJobRequest();
        int retryNum = 0;
        int sleepTime = 2000;
        request.withJobId(jobID);
        while (retryNum < 10) {
            ShowJobResponse response = ecsClient.showJob(request);
            ShowJobResponse.StatusEnum status = response.getStatus();
            if (response.getEntities() != null && response.getEntities().getSubJobs() != null) {
                for (SubJob sj : response.getEntities().getSubJobs()) {
                    serverIds.add(sj.getEntities().getServerId());
                }
            }
            if (serverIds.size() > 0 || status == ShowJobResponse.StatusEnum.FAIL || status == ShowJobResponse.StatusEnum.SUCCESS) {
                break;
            }
            try {
                Thread.sleep(sleepTime);
            } catch (Exception e) {
                e.printStackTrace();
            }
            retryNum++;
            sleepTime += 1000 * retryNum;
        }

        List<ServerDetail> instances = new ArrayList<>();
        if (serverIds.size() == 0) {
            return instances;
        }
        for (
                String srvId : serverIds) {
            try {
                ServerDetail sd = getServerDetail(srvId);
                instances.add(sd);
            } catch (SdkException e) {
                logProvisionInfo(e.getMessage());
            }
        }
        return instances;
    }

    private ServerDetail getServerDetail(String srvId) throws SdkException {
        ShowServerRequest request = new ShowServerRequest().withServerId(srvId);
        EcsClient ecsClient = parent.getEcsClient();
        ShowServerResponse response = ecsClient.showServer(request);
        return response.getServer();
    }

    private String createNewInstances(int needCreateCount) throws SdkException {
        //Limit the number of machines created each time prevent the failure of
        //each creation due to insufficient resources of HUAWEI CLOUD
        if (needCreateCount > 5) {
            needCreateCount = 5;
        }
        PostPaidServer serverBody = genPostPaidServer(needCreateCount, getZone(), getFlavorID(),
                getImgID(), parent.getVpcID(), description);
        //setting data volume
        if (isMountDV()) {
            List<PostPaidServerDataVolume> listServerDataVolumes = genDataVolume(getDvType(), getDvSize(), getMountQuantity());
            serverBody.withDataVolumes(listServerDataVolumes);
        }
        // setting network card
        List<PostPaidServerNic> listServerNics = genNicsData(chooseSubnetID());
        serverBody.withNics(listServerNics);
        //setting sys volume
        PostPaidServerRootVolume rootVolumeServer = genRootVolumeData(getRootVolumeTypeAttribute().toString(), rvSize);
        serverBody.withRootVolume(rootVolumeServer);
        //setting server tags
        List<PostPaidServerTag> postPaidServerTags = genTagsData(getTags());
        serverBody.withServerTags(postPaidServerTags);

        //setting server public ip
        if (associateEIP) {
            PostPaidServerPublicip publicIp = genEcsIP();
            serverBody.withPublicip(publicIp);
        }

        //setting key name
        if (getParent().isAssociateHWCKeypair()) {
            try {
                NovaKeypair keyPair = getParent().getKeyPair();
                if (keyPair != null) {
                    String name = keyPair.getName();
                    serverBody.withKeyName(name);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //set user data
        String uData = Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));
        serverBody.withUserData(uData);

        CreatePostPaidServersRequestBody body = new CreatePostPaidServersRequestBody();
        body.withDryRun(false).withServer(serverBody);
        CreatePostPaidServersRequest request = new CreatePostPaidServersRequest();
        request.withBody(body);

        EcsClient ecsClient = parent.getEcsClient();
        CreatePostPaidServersResponse response = ecsClient.createPostPaidServers(request);
        return response.getJobId();
    }

    private List<ServerDetail> findOrphansOrStopInstance(int number) {
        List<ServerDetail> tplAllInstance = tplAllInstance();
        List<ServerDetail> orphans = new ArrayList<>();
        int count = 0;
        for (ServerDetail instance : tplAllInstance) {
            if (checkInstance(instance)) {
                // instance is not connected to jenkins
                orphans.add(instance);
                count++;
            }
            if (count == number) {
                return orphans;
            }
        }
        return orphans;
    }

    List<ECSAbstractSlave> availableInstanceAsSlave(int number) throws IOException {
        List<ECSAbstractSlave> slaves = new ArrayList<>();
        List<ServerDetail> orphans = new ArrayList<>();
        List<ServerDetail> stopped = new ArrayList<>();
        for (ServerDetail sd : findOrphansOrStopInstance(number)) {
            if (VPCHelper.isShutdown(sd.getStatus())) {
                stopped.add(sd);
            } else {
                orphans.add(sd);
            }
        }
        if (stopped.size() > 0 && wakeUpInstance(stopped)) {
            slaves.addAll(toSlaves(stopped));
        }
        if (orphans.size() > 0) {
            slaves.addAll(toSlaves(orphans));
        }
        return slaves;
    }

    private boolean checkInstance(ServerDetail instance) {
        for (ECSAbstractSlave node : NodeIterator.nodes(ECSAbstractSlave.class)) {
            if (node.getInstanceId().equals(instance.getId()) && !VPCHelper.INSTANCE_STATE_SHUTOFF.equals(instance.getStatus())) {
                return false;
            }
        }
        return true;
    }

    private List<ECSAbstractSlave> toSlaves(List<ServerDetail> instances) throws IOException {
        try {
            logProvisionInfo("Return instance: " + instances.size());
            List<ECSAbstractSlave> slaves = new ArrayList<>(instances.size());
            for (ServerDetail instance : instances) {
                slaves.add(newOnDemandSlave(instance));
            }
            return slaves;
        } catch (Descriptor.FormException e) {
            throw new AssertionError(e); // we should have discovered all configuration issues upfront
        }
    }

    // Provisions a new ECS slave based on the currently running instance on ECS, instead of starting a new one.
    public ECSAbstractSlave attach(String instanceId, TaskListener listener) throws SdkException, IOException {
        try {
            LOGGER.info("Attaching to " + instanceId);
            ServerDetail instance = getServerDetail(instanceId);
            return newOnDemandSlave(instance);
        } catch (Descriptor.FormException e) {
            throw new AssertionError(); // we should have discovered all configuration issues upfront
        }
    }

    private ECSAbstractSlave newOnDemandSlave(ServerDetail instance) throws Descriptor.FormException, IOException {
        ECSAgentConfig.OnDemand config = new ECSAgentConfig.OnDemandBuilder()
                .withName(getSlaveName(instance.getId()))
                .withInstanceId(instance.getId())
                .withDescription(description)
                .withRemoteFS(remoteFS)
                .withNumExecutors(getNumExecutors())
                .withLabelString(labels)
                .withMode(mode)
                .withInitScript(initScript)
                .withTmpDir(tmpDir)
                .withRemoteAdmin(remoteAdmin)
                .withIdleTerminationMinutes(idleTerminationMinutes)
                .withOfflineTimeout(offlineTimeout)
                .withTags(ECSTag.formInstanceTags(VPCHelper.getServerTags(instance.getId(), parent)))
                .withCloudName(parent.name)
                .withLaunchTimeout(getLaunchTimeout())
                .withNodeProperties(nodeProperties.toList())
                .withStopOnTerminate(stopOnTerminate)
                .build();
        return ECSAgentFactory.getInstance().createOnDemandAgent(config);
    }


    private List<ServerDetail> tplAllInstance() {
        return VPCHelper.getAllOfServerByTmp(this);
    }

    public int getNumExecutors() {
        try {
            return Integer.parseInt(numExecutors);
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return 4;
        }
    }

    //Initializes data structure that we don't persist.
    protected Object readResolve() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        labelSet = Label.parse(labels);

        if (nodeProperties == null) {
            nodeProperties = new DescribableList<>(Saveable.NOOP);
        }

        if (instanceCap == 0) {
            instanceCap = Integer.MAX_VALUE;
        }

        if (imgType == null) {
            imgType = new DynamicImageData("");
        }

        return this;
    }

    @Override
    public String toString() {
        return "SlaveTemplate{" +
                "description='" + description + '\'' +
                ", labels='" + labels + '\'' +
                '}';
    }

    private void logProvisionInfo(String message) {
        LOGGER.info(this + ". " + message);
    }

    @Override
    public Descriptor<ECSTemplate> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }


    private static PostPaidServer genPostPaidServer(int needCreateCount, String zone, String flavorID, String imgID,
                                                    String vpcId, String description) {
        PostPaidServer serverBody = new PostPaidServer();
        String name = VPCHelper.genSlaveNamePrefix(description, flavorID) + getUUID8();
        serverBody.withAvailabilityZone(zone)
                .withCount(needCreateCount)
                .withFlavorRef(flavorID)
                .withImageRef(imgID)
                .withName(name).withVpcid(vpcId)
                .withDescription(description);
        return serverBody;
    }

    private static List<PostPaidServerDataVolume> genDataVolume(VolumeType volumeType, String volumeSize, String nums) {
        List<PostPaidServerDataVolume> dvs = new ArrayList<>();
        int vSize = 40;
        int vNums = 1;
        try {
            vSize = Integer.parseInt(volumeSize);
            vNums = Integer.parseInt(nums);
        } catch (Exception e) {
            LOGGER.warning(e.getMessage());
        }
        for (int i = 0; i < vNums; i++) {
            PostPaidServerDataVolume ppdv = new PostPaidServerDataVolume();
            ppdv.withVolumetype(PostPaidServerDataVolume.VolumetypeEnum.fromValue(volumeType.toString())).withSize(vSize);
            dvs.add(ppdv);
        }
        return dvs;
    }

    private static List<PostPaidServerNic> genNicsData(List<String> nics) {
        List<PostPaidServerNic> serverNicList = new ArrayList<>();
        if (nics == null) {
            return serverNicList;
        }
        if (nics.size() > 0) {
            for (String nic : nics) {
                PostPaidServerNic ppsNic = new PostPaidServerNic();
                ppsNic.withSubnetId(nic);
                serverNicList.add(ppsNic);
            }
        }
        return serverNicList;
    }

    private static PostPaidServerRootVolume genRootVolumeData(String vType, int rvSize) {
        PostPaidServerRootVolume rootVolumeServer = new PostPaidServerRootVolume();
        rootVolumeServer.withVolumetype(PostPaidServerRootVolume.VolumetypeEnum
                .fromValue(vType))
                .withSize(rvSize);
        return rootVolumeServer;
    }

    private static PostPaidServerPublicip genEcsIP() {
        PostPaidServerEipExtendParam extendParamEip = new PostPaidServerEipExtendParam();
        extendParamEip.withChargingMode(PostPaidServerEipExtendParam.ChargingModeEnum.fromValue("postPaid"));
        PostPaidServerEipBandwidth bandwidthEip = new PostPaidServerEipBandwidth();
        bandwidthEip.withSize(50)
                .withSharetype(PostPaidServerEipBandwidth.SharetypeEnum.fromValue("PER"))
                .withChargemode("traffic");
        PostPaidServerEip eipPublicIP = new PostPaidServerEip();
        eipPublicIP.withIptype("5_bgp")
                .withBandwidth(bandwidthEip)
                .withExtendparam(extendParamEip);
        PostPaidServerPublicip publicIPServer = new PostPaidServerPublicip();
        publicIPServer.withEip(eipPublicIP);
        return publicIPServer;
    }

    private static List<PostPaidServerTag> genTagsData(List<ECSTag> tags) {
        List<PostPaidServerTag> listServerServerTags = new ArrayList<>();
        if (tags == null) {
            return listServerServerTags;
        }
        for (ECSTag et : tags) {
            listServerServerTags.add(
                    new PostPaidServerTag().withKey(et.getName()).withValue(et.getValue())
            );
        }
        return listServerServerTags;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<ECSTemplate> {

        @Override
        public String getDisplayName() {
            return "";
        }

        public List<Descriptor<ImageTypeData>> getImageTypeDescriptors() {
            return Jenkins.get().getDescriptorList(ImageTypeData.class);
        }

        private VPC getCurVpc(String vpcID) {
            VPC curVpc = null;
            for (Cloud c : Jenkins.get().clouds) {
                if (c instanceof VPC) {
                    VPC vpc = (VPC) c;
                    if (vpc.getVpcID().equals(vpcID)) {
                        curVpc = vpc;
                        break;
                    }
                }
            }
            return curVpc;
        }

        public FormValidation doCheckDescription(@QueryParameter String description) {
            Jenkins.get().hasPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(description) == null) {
                return FormValidation.error("no set template desc");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckFlavorID(@QueryParameter String flavorID) {
            Jenkins.get().hasPermission(Jenkins.ADMINISTER);
            if (StringUtils.isBlank(flavorID)) {
                return FormValidation.error(Messages.TPL_NoSetFlavorID());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckZone(@QueryParameter String zone) {
            Jenkins.get().hasPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(zone) == null) {
                return FormValidation.error(Messages.TPL_NoSetAZ());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckDvSize(@QueryParameter String dvSize) {
            if (Util.fixEmptyAndTrim(dvSize) == null) {
                return FormValidation.error("no data volume size is specified");
            }
            try {
                int size = Integer.parseInt(dvSize);
                if (size >= 10 && size <= 32768) {
                    return FormValidation.ok();
                }
                return FormValidation.error("The data volume size should be between 10-32768");
            } catch (Exception e) {
                LOGGER.info("data volume size fill error");
                return FormValidation.error("Please fill in the correct data volume size");
            }
        }

        public FormValidation doCheckMountQuantity(@QueryParameter String mountQuantity) {
            try {
                int sz = Integer.parseInt(mountQuantity);
                if (sz > 23 || sz <= 0) {
                    return FormValidation.error("Please fill in the correct data volume quantity");
                }
            } catch (Exception e) {
                LOGGER.info(e.getMessage());
                return FormValidation.error("Please fill in the correct data volume quantity");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckMinimumNumberOfInstances(@QueryParameter String value, @QueryParameter String instanceCapStr) {
            if (Util.fixEmptyAndTrim(value) == null)
                return FormValidation.ok();
            try {
                int val = Integer.parseInt(value);
                if (val >= 0) {
                    int instanceCap;
                    try {
                        instanceCap = Integer.parseInt(instanceCapStr);
                    } catch (NumberFormatException ignore) {
                        instanceCap = Integer.MAX_VALUE;
                    }
                    if (val > instanceCap) {
                        return FormValidation
                                .error("Minimum number of instances must not be larger than AMI Instance Cap %d",
                                        instanceCap);
                    }
                    return FormValidation.ok();
                }
            } catch (NumberFormatException e) {
                LOGGER.log(Level.INFO, e.getMessage());
            }
            return FormValidation.error("Minimum number of instances must be a non-negative integer (or null)");
        }

        public FormValidation doCheckRvSizeStr(@QueryParameter String rvSizeStr) {
            LOGGER.log(Level.WARNING, rvSizeStr + "");
            if (Util.fixEmptyAndTrim(rvSizeStr) != null) {
                try {
                    int size = Integer.parseInt(rvSizeStr);
                    if (size < 0 || size > 1024) {
                        return FormValidation.error("Wrong value is set");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return FormValidation.error("Wrong value is set");
                }
            }
            return FormValidation.ok();

        }

        public FormValidation doCheckSubnetIDs(@QueryParameter String subnetIDs) {
            Jenkins.get().hasPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(subnetIDs) == null) {
                return FormValidation.error("no subnetIDs is specified");
            }
            return FormValidation.ok();
        }


        public FormValidation doCheckLabelString(@QueryParameter String value, @QueryParameter Node.Mode mode) {
            if (mode == Node.Mode.EXCLUSIVE && (value == null || value.trim().isEmpty())) {
                return FormValidation.warning("You may want to assign labels to this node;"
                        + " it's marked to only run jobs that are exclusively tied to itself or a label.");
            }

            return FormValidation.ok();
        }

        @RequirePOST
        public ListBoxModel doFillZoneItems(@QueryParameter String zone, @RelativePath("..") @QueryParameter String region,
                                            @RelativePath("..") @QueryParameter String credentialsId) {
            checkPermission(VPC.PROVISION);
            ListBoxModel model = new ListBoxModel();
            if (StringUtils.isBlank(zone)) {
                model.add(new ListBoxModel.Option(Messages.UI_NoSelect(), "", true));
            }
            if (StringUtils.isEmpty(region) || StringUtils.isEmpty(credentialsId)) {
                return model;
            }
            EcsClient ecsClient = VPC.createEcsClient(region, credentialsId);
            NovaListAvailabilityZonesRequest request = new NovaListAvailabilityZonesRequest();
            try {
                NovaListAvailabilityZonesResponse response = ecsClient.novaListAvailabilityZones(request);
                List<NovaAvailabilityZone> availabilityZoneInfo = response.getAvailabilityZoneInfo();
                for (NovaAvailabilityZone az : availabilityZoneInfo) {
                    if (az.getZoneName().equals(zone)) {
                        model.add(new ListBoxModel.Option(az.getZoneName(), az.getZoneName(), true));
                    } else {
                        model.add(new ListBoxModel.Option(az.getZoneName(), az.getZoneName(), false));
                    }
                }
            } catch (SdkException e) {
                LOGGER.log(Level.WARNING, e.getMessage());
            }
            return model;
        }

        @RequirePOST
        public ListBoxModel doFillRootVolumeTypeItems(@QueryParameter String rootVolumeType) {
            return Stream.of(VolumeType.values())
                    .map(v -> {
                        if (v.name().equals(rootVolumeType)) {
                            return new ListBoxModel.Option(v.name(), v.name(), true);
                        } else {
                            return new ListBoxModel.Option(v.name(), v.name(), false);
                        }
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        @RequirePOST
        public ListBoxModel doFillDvTypeItems(@QueryParameter String dvType) {
            return Stream.of(VolumeType.values())
                    .map(v -> {
                        if (v.name().equals(dvType)) {
                            return new ListBoxModel.Option(v.name(), v.name(), true);
                        } else {
                            return new ListBoxModel.Option(v.name(), v.name(), false);
                        }
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        private void checkPermission(Permission p) {
            final VPC ancestorObject = Stapler.getCurrentRequest().findAncestorObject(VPC.class);
            if (ancestorObject != null) {
                ancestorObject.checkPermission(p);
            } else {
                Jenkins.get().checkPermission(p);
            }
        }

        @RequirePOST
        public FormValidation doTestCreateEcs(@QueryParameter String region, @QueryParameter String credentialsId,
                                              @QueryParameter String description, @QueryParameter String flavorID,
                                              @QueryParameter String zone, @QueryParameter String vpcID,
                                              @QueryParameter VolumeType rootVolumeType, @QueryParameter VolumeType dvType,
                                              @QueryParameter String rvSizeStr, @QueryParameter boolean associateEIP,
                                              @QueryParameter String subnetIDs, @QueryParameter String dvSize,
                                              @QueryParameter String mountQuantity, @QueryParameter boolean mountDV) {
            checkPermission(VPC.PROVISION);

            if (StringUtils.isEmpty(vpcID) || StringUtils.isEmpty(description) ||
                    StringUtils.isEmpty(region) || StringUtils.isEmpty(credentialsId) ||
                    StringUtils.isEmpty(flavorID) || StringUtils.isEmpty(zone) ||
                    StringUtils.isBlank(subnetIDs)) {
                return FormValidation.error(Messages.HuaweiECSCloud_ErrorConfig());
            }
            VPC curVpc = getCurVpc(vpcID);
            String imgID = "";
            if (curVpc != null) {
                ECSTemplate tmp = curVpc.getTemplate(description);
                if (tmp != null) {
                    if (tmp.imgType.isDynamicType()) {
                        ImsClient client = VPC.createImsClient(region, credentialsId);
                        String tag = ((DynamicImageData) tmp.imgType).getImgTag();
                        try {
                            imgID = VPCHelper.getImageIDByTag(client, tag);
                        } catch (Exception e) {
                            LOGGER.info(e.getMessage());
                        }
                    } else {
                        imgID = ((StaticImageData) tmp.imgType).getImageId();
                    }
                } else {
                    return FormValidation.error(Messages.TPL_NotApplyModify());
                }

            }

            if (Util.fixEmptyAndTrim(imgID) == null) {
                return FormValidation.error(Messages.TPL_CantGetImgID());
            }

            PostPaidServer serverBody = genPostPaidServer(1, zone, flavorID,
                    imgID, vpcID, description);
            //setting data volume
            if (mountDV) {
                List<PostPaidServerDataVolume> listServerDataVolumes = genDataVolume(dvType, dvSize, mountQuantity);
                serverBody.withDataVolumes(listServerDataVolumes);
            }
            // setting network card
            String[] s = subnetIDs.split(" ");
            List<String> subnetIDList = new ArrayList<>(Arrays.asList(s));
            List<PostPaidServerNic> listServerNic = genNicsData(subnetIDList);
            serverBody.withNics(listServerNic);
            //setting sys volume
            int rvSize = 0;
            try {
                rvSize = Integer.parseInt(rvSizeStr);
            } catch (Exception e) {
                LOGGER.info(e.getMessage());
            }
            PostPaidServerRootVolume rootVolumeServer = genRootVolumeData(rootVolumeType.toString(), rvSize);
            serverBody.withRootVolume(rootVolumeServer);
            CreatePostPaidServersRequestBody body = new CreatePostPaidServersRequestBody();
            if (associateEIP) {
                PostPaidServerPublicip publicIp = genEcsIP();
                serverBody.withPublicip(publicIp);
            }
            body.withDryRun(true).withServer(serverBody);
            CreatePostPaidServersRequest request = new CreatePostPaidServersRequest();
            request.withBody(body);

            EcsClient ecsClient = VPC.createEcsClient(region, credentialsId);
            try {
                CreatePostPaidServersResponse response = ecsClient.createPostPaidServers(request);
                if (response.getHttpStatusCode() == 202) {
                    return FormValidation.ok(Messages.HuaweiECSCloud_Success());
                } else {
                    return FormValidation.ok("http response code is not 202");
                }
            } catch (SdkException e) {
                return FormValidation.error(e.getMessage());
            }
        }

    }

    private static String getUUID8() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase().substring(0, 8);
    }
}
