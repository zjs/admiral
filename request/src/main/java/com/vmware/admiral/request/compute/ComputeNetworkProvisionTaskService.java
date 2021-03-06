/*
 * Copyright (c) 2016-2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.request.compute;

import static com.vmware.admiral.compute.network.ComputeNetworkCIDRAllocationService.ComputeNetworkCIDRAllocationRequest.allocationRequest;
import static com.vmware.admiral.request.utils.RequestUtils.FIELD_NAME_CONTEXT_ID_KEY;
import static com.vmware.photon.controller.model.tasks.ProvisionSecurityGroupTaskService.NETWORK_STATE_ID_PROP_NAME;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.vmware.admiral.common.DeploymentProfileConfig;
import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.ServiceUtils;
import com.vmware.admiral.compute.network.ComputeNetworkCIDRAllocationService.ComputeNetworkCIDRAllocationRequest;
import com.vmware.admiral.compute.network.ComputeNetworkCIDRAllocationService.ComputeNetworkCIDRAllocationState;
import com.vmware.admiral.compute.network.ComputeNetworkDescriptionService.ComputeNetworkDescription;
import com.vmware.admiral.compute.network.ComputeNetworkDescriptionService.NetworkType;
import com.vmware.admiral.compute.network.ComputeNetworkService.ComputeNetwork;
import com.vmware.admiral.compute.profile.NetworkProfileService.NetworkProfile.IsolationSupportType;
import com.vmware.admiral.compute.profile.ProfileService.ProfileStateExpanded;
import com.vmware.admiral.request.compute.ComputeNetworkProvisionTaskService.ComputeNetworkProvisionTaskState.SubStage;
import com.vmware.admiral.request.utils.RequestUtils;
import com.vmware.admiral.service.common.AbstractTaskStatefulService;
import com.vmware.photon.controller.model.UriPaths;
import com.vmware.photon.controller.model.UriPaths.AdapterTypePath;
import com.vmware.photon.controller.model.adapterapi.SecurityGroupInstanceRequest;
import com.vmware.photon.controller.model.adapterapi.SubnetInstanceRequest.InstanceRequestType;
import com.vmware.photon.controller.model.adapters.registry.PhotonModelAdaptersRegistryService;
import com.vmware.photon.controller.model.adapters.registry.PhotonModelAdaptersRegistryService.PhotonModelAdapterConfig;
import com.vmware.photon.controller.model.query.QueryUtils;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeStateWithDescription;
import com.vmware.photon.controller.model.resources.EndpointService.EndpointState;
import com.vmware.photon.controller.model.resources.LoadBalancerDescriptionService.LoadBalancerDescription;
import com.vmware.photon.controller.model.resources.LoadBalancerService.LoadBalancerState;
import com.vmware.photon.controller.model.resources.NetworkInterfaceDescriptionService.NetworkInterfaceDescription;
import com.vmware.photon.controller.model.resources.NetworkInterfaceService;
import com.vmware.photon.controller.model.resources.NetworkInterfaceService.NetworkInterfaceState;
import com.vmware.photon.controller.model.resources.NetworkService.NetworkState;
import com.vmware.photon.controller.model.resources.ResourceState;
import com.vmware.photon.controller.model.resources.SecurityGroupService;
import com.vmware.photon.controller.model.resources.SecurityGroupService.SecurityGroupState;
import com.vmware.photon.controller.model.resources.SecurityGroupService.SecurityGroupState.Rule;
import com.vmware.photon.controller.model.resources.SecurityGroupService.SecurityGroupState.Rule.Access;
import com.vmware.photon.controller.model.resources.SubnetService;
import com.vmware.photon.controller.model.resources.SubnetService.SubnetState;
import com.vmware.photon.controller.model.support.LifecycleState;
import com.vmware.photon.controller.model.tasks.ProvisionSecurityGroupTaskService;
import com.vmware.photon.controller.model.tasks.ProvisionSecurityGroupTaskService.ProvisionSecurityGroupTaskState;
import com.vmware.photon.controller.model.tasks.ProvisionSubnetTaskService;
import com.vmware.photon.controller.model.tasks.ProvisionSubnetTaskService.ProvisionSubnetTaskState;
import com.vmware.photon.controller.model.tasks.ServiceTaskCallback;
import com.vmware.photon.controller.model.tasks.TaskOption;
import com.vmware.photon.controller.model.util.AssertUtil;
import com.vmware.xenon.common.DeferredResult;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyIndexingOption;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask.Query.Builder;

/**
 * Task implementing the provisioning of a compute network.
 */
public class ComputeNetworkProvisionTaskService
        extends
        AbstractTaskStatefulService<ComputeNetworkProvisionTaskService.ComputeNetworkProvisionTaskState, ComputeNetworkProvisionTaskService.ComputeNetworkProvisionTaskState.SubStage> {

    public static final String FACTORY_LINK =
            ManagementUriParts.REQUEST_PROVISION_COMPUTE_NETWORK_TASKS;

    public static final String DISPLAY_NAME = "Compute Network Provision";

    public static class ComputeNetworkProvisionTaskState extends
            com.vmware.admiral.service.common.TaskServiceDocument<ComputeNetworkProvisionTaskState.SubStage> {

        public enum SubStage {
            CREATED, START_PROVISIONING, PROVISIONING, COMPLETED, ERROR;

            static final Set<SubStage> TRANSIENT_SUB_STAGES = new HashSet<>(
                    Collections.singletonList(PROVISIONING));
        }

        /**
         * (Required) The description that defines the requested resource.
         */
        @Documentation(description = "Type of resource to create.")
        @PropertyOptions(indexing = PropertyIndexingOption.STORE_ONLY, usage = {
                PropertyUsageOption.REQUIRED, PropertyUsageOption.SINGLE_ASSIGNMENT })
        public String resourceDescriptionLink;

        /**
         * (Required) Number of resources to provision.
         */
        @Documentation(description = "Number of resources to provision.")
        @PropertyOptions(indexing = PropertyIndexingOption.STORE_ONLY, usage = {
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public Long resourceCount;

        /**
         * (Required) Links to already allocated resources that are going to be provisioned.
         */
        @Documentation(
                description = "Links to already allocated resources that are going to be provisioned.")
        @PropertyOptions(indexing = PropertyIndexingOption.STORE_ONLY, usage = {
                PropertyUsageOption.REQUIRED, PropertyUsageOption.SINGLE_ASSIGNMENT })
        public Set<String> resourceLinks;

        // Service use fields:

        /**
         * (Internal) Reference to the adapter that will fulfill the provision request.
         */
        @Documentation(
                description = "Reference to the adapter that will fulfill the provision request.")
        @PropertyOptions(indexing = PropertyIndexingOption.STORE_ONLY, usage = {
                PropertyUsageOption.SERVICE_USE, PropertyUsageOption.SINGLE_ASSIGNMENT,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public URI instanceAdapterReference;

    }

    public static class ConnectedResource {
        public ResourceState resource;
        public ResourceState description;
        public NetworkInterfaceDescription networkInterfaceDescription;

        public static ConnectedResource create(ResourceState resource, ResourceState description) {
            ConnectedResource result = new ConnectedResource();
            result.resource = resource;
            result.description = description;
            return result;
        }
    }

    private static class Context {
        public Context(String computeNetworkLink, ComputeNetworkProvisionTaskState state,
                ServiceTaskCallback<SubStage> serviceTaskCallback) {
            this.computeNetworkLink = computeNetworkLink;
            this.state = state;
            this.serviceTaskCallback = serviceTaskCallback;
        }

        public String computeNetworkLink;
        public ComputeNetwork computeNetwork;
        public ProfileStateExpanded profile;
        public SubnetState subnet;
        public EndpointState isolatedNetworkEndpoint;
        public String subnetInstanceAdapterReference;
        public String securityGroupInstanceAdapterReference;
        public String subnetCIDR;
        public ComputeNetworkDescription computeNetworkDescription;
        public List<ConnectedResource> connectedResources;
        public ComputeNetworkProvisionTaskState state;
        public SecurityGroupState isolationSecurityGroup;
        public NetworkState subnetNetworkState;
        public ComputeStateWithDescription endpointComputeState;
        public ServiceTaskCallback<SubStage> serviceTaskCallback;
    }

    public ComputeNetworkProvisionTaskService() {
        super(ComputeNetworkProvisionTaskState.class, SubStage.class, DISPLAY_NAME);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
        super.transientSubStages = SubStage.TRANSIENT_SUB_STAGES;
    }

    @Override
    protected void validateStateOnStart(ComputeNetworkProvisionTaskState state) {
        state.resourceCount = (long) state.resourceLinks.size();

        if (state.resourceCount < 1) {
            throw new LocalizableValidationException("'resourceCount' must be greater than 0.",
                    "request.resource-count.zero");
        }
    }

    @Override
    protected void handleStartedStagePatch(ComputeNetworkProvisionTaskState state) {
        switch (state.taskSubStage) {
        case CREATED:
            provisionResources(state);
            break;
        case PROVISIONING:
            break;
        case COMPLETED:
            complete();
            break;
        case ERROR:
            completeWithError();
            break;
        default:
            break;
        }
    }

    private void provisionResources(ComputeNetworkProvisionTaskState state) {
        try {
            Set<String> resourceLinks = state.resourceLinks;
            if (resourceLinks == null || resourceLinks.isEmpty()) {
                throw new LocalizableValidationException(
                        "No compute network instances to provision",
                        "request.compute.network.provision.empty");
            }
            ServiceTaskCallback<SubStage> callback = ServiceTaskCallback.create(getUri());
            callback.onSuccessTo(SubStage.COMPLETED);
            callback.onErrorTo(SubStage.ERROR);

            resourceLinks.forEach(computeNetworkLink -> DeferredResult
                    .completed(new Context(computeNetworkLink, state, callback))
                    .thenCompose(this::populateContext)
                    .thenCompose(this::provisionResource)
                    .exceptionally(t -> {
                        logSevere("Failure provisioning a network: %s", Utils.toString(t));
                        callback.sendResponse(this, t);
                        return null;
                    }));

            logInfo("Requested provisioning of %s compute network resources.",
                    resourceLinks.size());
            proceedTo(SubStage.PROVISIONING);
        } catch (Throwable e) {
            failTask("System failure creating SubnetStates", e);
        }
    }

    private DeferredResult<Context> populateContext(Context context) {
        return DeferredResult.completed(context)
                .thenCompose(this::populateComputeNetwork)
                .thenCompose(this::populateComputeNetworkDescription)
                .thenCompose(this::populateProfile)
                .thenCompose(this::populateConnectedResources)
                .thenCompose(this::populateSubnet)
                .thenCompose(ctx -> {
                    if (context.computeNetwork.networkType != NetworkType.ISOLATED) {
                        return DeferredResult.completed(context);
                    } else {
                        // Get isolated network context
                        return DeferredResult.completed(context)
                                .thenCompose(this::populateEndpoint)
                                .thenCompose(this::populateInstanceAdapterReference);
                    }
                });
    }

    private DeferredResult<Context> provisionResource(Context context) {
        if (context.computeNetwork.networkType == NetworkType.ISOLATED &&
                context.profile.networkProfile.isolationType == IsolationSupportType.SUBNET) {
            // Provision a new subnet
            if (context.subnet == null) {
                throw new IllegalArgumentException(
                        String.format("Subnet is required to provision an ISOLATED network '%s'.",
                                context.computeNetworkDescription.name));
            }
            return DeferredResult.completed(context)
                    .thenCompose(this::allocateSubnetCIDR)
                    .thenCompose(this::createSubnet)
                    .thenCompose(this::configureConnectedResources)
                    .thenCompose(this::provisionSubnet);
        } else if (context.computeNetwork.networkType == NetworkType.ISOLATED &&
                context.profile.networkProfile.isolationType
                        == IsolationSupportType.SECURITY_GROUP) {
            // Provision a new security group
            return DeferredResult.completed(context)
                    .thenCompose(this::populateEndpointComputeState)
                    .thenCompose(this::createSecurityGroup)
                    .thenCompose(this::configureConnectedResources)
                    .thenCompose(this::pupulateSubnetNetworkState)
                    .thenCompose(this::provisionSecurityGroup);
        } else {
            // No new resources need to be provisioned.
            // Simply create the NIC states and finish the task.
            return DeferredResult.completed(context)
                    .thenCompose(this::configureConnectedResources)
                    .thenCompose(ctx -> {
                        context.serviceTaskCallback.sendResponse(this, (Throwable)null);
                        return DeferredResult.completed(ctx);
                    });
        }
    }

    private DeferredResult<Context> populateComputeNetwork(Context context) {
        return this.sendWithDeferredResult(
                Operation.createGet(this, context.computeNetworkLink), ComputeNetwork.class)
                .thenApply(computeNetwork -> {
                    context.computeNetwork = computeNetwork;
                    return context;
                });
    }

    private DeferredResult<Context> populateComputeNetworkDescription(Context context) {
        return this.sendWithDeferredResult(
                Operation.createGet(this, context.computeNetwork.descriptionLink),
                ComputeNetworkDescription.class)
                .thenApply(cnd -> {
                    context.computeNetworkDescription = cnd;
                    return context;
                });
    }

    private DeferredResult<Context> populateProfile(Context context) {
        AssertUtil.assertNotNull(context.computeNetwork.provisionProfileLink,
                "Context.computeNetwork.provisionProfileLink should not be null");

        URI uri = UriUtils.buildUri(this.getHost(), context.computeNetwork.provisionProfileLink);
        uri = UriUtils.buildExpandLinksQueryUri(uri);

        return this.sendWithDeferredResult(Operation.createGet(uri), ProfileStateExpanded.class)
                .thenApply(profile -> {
                    context.profile = profile;
                    return context;
                });
    }

    private DeferredResult<Context> populateEndpoint(Context context) {
        if (context.profile == null
                || context.profile.networkProfile.isolatedNetworkState == null) {
            return DeferredResult.completed(context);
        } else {
            // Use network profile isolated network endpoint link to provision subnet
            // In case of NSX-T isolated networks, profile endpoint will be vSphere
            // and network profile Isolated network endpoint will be NSX-T
            return this.sendWithDeferredResult(Operation.createGet(this.getHost(),
                    context.profile.networkProfile.isolatedNetworkState.endpointLink),
                    EndpointState.class)
                    .thenApply(endpoint -> {
                        context.isolatedNetworkEndpoint = endpoint;
                        return context;
                    });
        }
    }

    private DeferredResult<Context> populateInstanceAdapterReference(Context context) {
        if (context.profile.networkProfile.isolationType.equals(IsolationSupportType.NONE)) {
            return DeferredResult.completed(context);
        } else {
            if (context.profile.networkProfile.isolationType.equals(IsolationSupportType.SUBNET)) {
                AssertUtil.assertNotNull(context.isolatedNetworkEndpoint.endpointType,
                        "Context.isolatedNetworkEndpoint.endpointType should not be null.");
                return sendWithDeferredResult(
                        Operation.createGet(getHost(),
                                UriUtils.buildUriPath(
                                        PhotonModelAdaptersRegistryService.FACTORY_LINK,
                                        context.isolatedNetworkEndpoint.endpointType)),
                        PhotonModelAdapterConfig.class)
                        .thenApply(config -> {
                            context.subnetInstanceAdapterReference = config.adapterEndpoints
                                    .get(UriPaths.AdapterTypePath.SUBNET_ADAPTER.key);
                            return context;
                        });
            } else if (context.profile.networkProfile.isolationType.equals
                    (IsolationSupportType.SECURITY_GROUP)) {
                AssertUtil.assertNotNull(context.profile.endpoint.endpointType,
                        "Context.profile.endpoint.endpointType should not be null.");
                return sendWithDeferredResult(
                        Operation.createGet(getHost(),
                                UriUtils.buildUriPath(
                                        PhotonModelAdaptersRegistryService.FACTORY_LINK,
                                        context.profile.endpoint.endpointType)),
                        PhotonModelAdapterConfig.class)
                        .thenApply(config -> {
                            context.securityGroupInstanceAdapterReference = config.adapterEndpoints
                                    .get(AdapterTypePath.SECURITY_GROUP_ADAPTER.key);
                            return context;
                        });
            } else {
                return DeferredResult.completed(context);
            }
        }
    }

    private DeferredResult<Context> populateConnectedResources(Context context) {
        return DeferredResult.completed(context)
                .thenApply(ignore -> {
                    context.connectedResources = new ArrayList<>();
                    return context;
                })
                .thenCompose(this::populateComputeStates)
                .thenCompose(this::populateLoadBalancers);
    }

    private DeferredResult<Context> populateComputeStates(Context context) {
        // get all ComputeStates that have the same context id as this compute network
        Builder builder = Builder.create()
                .addKindFieldClause(ComputeState.class);
        builder.addCompositeFieldClause(ComputeState.FIELD_NAME_CUSTOM_PROPERTIES,
                FIELD_NAME_CONTEXT_ID_KEY, RequestUtils.getContextId(context.state));
        QueryUtils.QueryByPages<ComputeState> query = new QueryUtils.QueryByPages<>(
                getHost(),
                builder.build(), ComputeState.class, context.state.tenantLinks);

        // collect the ones connected to this network
        return query.collectDocuments(Collectors.toList())
                .thenCompose(computes -> DeferredResult.allOf(
                        computes.stream().map(compute -> populateComputeState(context, compute))
                                .collect(Collectors.toList())))
                .thenApply(resources -> resources.stream()
                        .filter(r -> r.networkInterfaceDescription != null)
                        .collect(Collectors.toList()))
                .thenApply(resources -> {
                    context.connectedResources.addAll(resources);
                    return context;
                });
    }

    private DeferredResult<ConnectedResource> populateComputeState(Context context,
            ComputeState compute) {
        return getDocumentDR(compute.descriptionLink, ComputeDescription.class)
                .thenApply(cd -> ConnectedResource.create(compute, cd))
                .thenCompose(cr -> getDocumentsDR(
                        ((ComputeDescription) cr.description).networkInterfaceDescLinks,
                        NetworkInterfaceDescription.class).thenApply(nids -> nids.stream()
                                .filter(nid -> context.computeNetworkDescription.name != null
                                        && context.computeNetworkDescription.name.equals(nid.name))
                                .collect(Collectors.toList())).thenApply(nids -> {
                                    if (nids.size() > 1) {
                                        throw new LocalizableValidationException(
                                                "Cannot have multiple NICs connected to the same network",
                                                "request.compute.network.provision.multiple-nics");
                                    }
                                    if (!nids.isEmpty()) {
                                        cr.networkInterfaceDescription = nids.get(0);
                                    }
                                    return cr;
                                }));
    }

    private DeferredResult<Context> populateLoadBalancers(Context context) {
        // get all load balancers that have the same context id as this compute network
        Builder builder = Builder.create()
                .addKindFieldClause(LoadBalancerState.class);
        builder.addCompositeFieldClause(ComputeState.FIELD_NAME_CUSTOM_PROPERTIES,
                FIELD_NAME_CONTEXT_ID_KEY, RequestUtils.getContextId(context.state));
        QueryUtils.QueryByPages<LoadBalancerState> query = new QueryUtils.QueryByPages<>(
                getHost(),
                builder.build(), LoadBalancerState.class, context.state.tenantLinks);

        return query.collectDocuments(Collectors.toList())
                .thenCompose(lbs -> DeferredResult.allOf(
                        lbs.stream().map(lb -> populateLoadBalancer(context, lb))
                                .collect(Collectors.toList())))
                .thenApply(resources -> {
                    context.connectedResources.addAll(resources);
                    return context;
                });
    }

    private DeferredResult<ConnectedResource> populateLoadBalancer(Context context,
            LoadBalancerState loadBalancer) {
        return getDocumentDR(loadBalancer.descriptionLink, LoadBalancerDescription.class)
                .thenApply(lbd -> ConnectedResource.create(loadBalancer, lbd));
    }

    private DeferredResult<Context> populateSubnet(Context context) {
        AssertUtil.assertNotNull(context.profile, "Context.profile should not be null.");
        AssertUtil.assertNotNull(context.computeNetwork,
                "Context.computeNetwork should not be null.");
        AssertUtil.assertNotNull(context.computeNetwork.groupLinks,
                "Context.computeNetwork.groupLinks should not be null.");
        AssertUtil.assertNotNull(context.profile.networkProfile,
                "Context.profile.networkProfile should not be null.");

        if (context.computeNetwork.subnetLink != null) {
            return this.sendWithDeferredResult(
                    Operation.createGet(this, context.computeNetwork.subnetLink), SubnetState.class)
                    .thenApply(subnetState -> {
                        context.subnet = subnetState;
                        return context;
                    });
        } else if (context.computeNetwork.networkType == NetworkType.ISOLATED &&
                context.profile.networkProfile.isolationType == IsolationSupportType.SUBNET) {
            // Create a new subnet template to attach to the VM NICs
            SubnetState subnet = new SubnetState();
            subnet.id = UUID.randomUUID().toString();
            subnet.name = context.computeNetwork.name;
            subnet.networkLink = context.computeNetwork.documentSelfLink;
            subnet.tenantLinks = context.computeNetwork.tenantLinks;
            subnet.groupLinks = context.computeNetwork.groupLinks;

            subnet.lifecycleState = LifecycleState.PROVISIONING;

            subnet.customProperties = context.computeNetwork.customProperties;

            context.subnet = subnet;

            return DeferredResult.completed(context);
        } else {
            // no subnet is necessary
            return DeferredResult.completed(context);
        }
    }

    private DeferredResult<Context> createSecurityGroup(Context context) {
        AssertUtil.assertNotNull(context.profile, "Context.profile should not be null.");
        AssertUtil.assertNotNull(context.computeNetwork,
                "Context.computeNetwork should not be null.");
        AssertUtil.assertNotNull(context.computeNetwork.groupLinks,
                "Context.computeNetwork.groupLinks should not be null.");
        AssertUtil.assertNotNull(context.profile.networkProfile,
                "Context.profile.networkProfile should not be null.");
        AssertUtil.assertTrue(context.computeNetwork.networkType.equals(NetworkType.ISOLATED),
                "Context.computeNetwork.networkType should be ISOLATED");
        AssertUtil.assertTrue(context.profile.networkProfile.isolationType.equals(
                IsolationSupportType.SECURITY_GROUP),
                "Context.profile.networkProfile.isolationType should be SECURITY_GROUP");
        AssertUtil.assertNotNull(context.endpointComputeState,
                "Context.endpointComputeState should not be null");

        // Create a new security group for this network
        SecurityGroupState securityGroup = new SecurityGroupState();
        securityGroup.id = UUID.randomUUID().toString();
        securityGroup.documentSelfLink = securityGroup.id;
        securityGroup.name =
                String.format("isolation-network-%s-deployment-%s",
                        context.computeNetworkDescription.name,
                        RequestUtils.getContextId(context.state));
        securityGroup.desc = securityGroup.name;
        securityGroup.regionId = context.endpointComputeState.description.regionId;
        securityGroup.endpointLink = context.profile.endpointLink;
        securityGroup.tenantLinks = context.state.tenantLinks;
        securityGroup.instanceAdapterReference =
                URI.create(context.securityGroupInstanceAdapterReference);
        securityGroup.resourcePoolLink = context.profile.endpoint.resourcePoolLink;
        securityGroup.authCredentialsLink = context.profile.endpoint.authCredentialsLink;
        securityGroup.groupLinks = context.computeNetwork.groupLinks;
        String contextId = RequestUtils.getContextId(context.state);
        if (contextId != null) {
            securityGroup.customProperties = new HashMap<>();
            securityGroup.customProperties.put(FIELD_NAME_CONTEXT_ID_KEY, contextId);
        }

        // build "deny-all" rules for now
        securityGroup.ingress = buildIsolationRules("inbound");
        securityGroup.egress = buildIsolationRules("outbound");

        return this.sendWithDeferredResult(
                Operation.createPost(this, SecurityGroupService.FACTORY_LINK)
                        .setBody(securityGroup), SecurityGroupState.class)
                .thenApply(sg -> {
                    context.isolationSecurityGroup = sg;
                    return context;
                })
                .thenCompose(this::patchComputeNetwork);
    }

    private DeferredResult<Context> allocateSubnetCIDR(Context context) {
        AssertUtil.assertNotNull(context.profile, "Context.profile should not be null.");
        AssertUtil.assertNotNull(context.profile.networkProfile.isolatedSubnetCIDRPrefix,
                "Context.profile.networkProfile.isolatedSubnetCIDRPrefix should "
                        + "not be null.");
        AssertUtil.assertNotNull(context.subnet, "Context.subnet should not be null.");

        String optionalNetworkCIDR = context.profile.networkProfile.isolationNetworkCIDR;
        ComputeNetworkCIDRAllocationRequest request =
                allocationRequest(context.subnet.id,
                        context.profile.networkProfile.isolatedSubnetCIDRPrefix,
                        optionalNetworkCIDR);
        return this.sendWithDeferredResult(
                Operation.createPatch(this,
                        context.profile.networkProfile.isolationNetworkCIDRAllocationLink)
                        .setBody(request),
                ComputeNetworkCIDRAllocationState.class)
                .thenApply(cidrAllocation -> {
                    // Store the allocated CIDR in the context.
                    context.subnetCIDR = cidrAllocation.allocatedCIDRs.get(request.subnetId);
                    return context;
                });
    }

    private DeferredResult<Context> createSubnet(Context context) {
        ProfileStateExpanded profile = context.profile;
        SubnetState subnet = context.subnet;

        subnet.networkLink = profile.networkProfile.isolationNetworkLink;
        subnet.endpointLink = context.isolatedNetworkEndpoint.documentSelfLink;
        subnet.instanceAdapterReference = URI.create(context.subnetInstanceAdapterReference);
        subnet.subnetCIDR = context.subnetCIDR;
        subnet.groupLinks = context.computeNetwork.groupLinks;
        String contextId = RequestUtils.getContextId(context.state);
        if (contextId != null) {
            subnet.customProperties = new HashMap<>();
            subnet.customProperties.put(FIELD_NAME_CONTEXT_ID_KEY, contextId);
        }

        return this.sendWithDeferredResult(
                Operation.createPost(this, SubnetService.FACTORY_LINK)
                        .setBody(subnet), SubnetState.class)
                .thenApply(subnetState -> context.subnet = subnetState)
                .thenCompose(subnetState -> patchComputeNetwork(context));
    }

    private DeferredResult<Context> provisionSubnet(Context context) {

        ProvisionSubnetTaskState provisionTaskState = new ProvisionSubnetTaskState();
        boolean isMockRequest = DeploymentProfileConfig.getInstance().isTest();
        if (isMockRequest) {
            provisionTaskState.options = EnumSet.of(TaskOption.IS_MOCK);
        }
        provisionTaskState.requestType = InstanceRequestType.CREATE;
        provisionTaskState.serviceTaskCallback = context.serviceTaskCallback;
        provisionTaskState.tenantLinks = context.computeNetwork.tenantLinks;
        provisionTaskState.documentExpirationTimeMicros = ServiceUtils
                .getDefaultTaskExpirationTimeInMicros();
        provisionTaskState.subnetLink = context.subnet.documentSelfLink;

        return this.sendWithDeferredResult(
                Operation.createPost(this, ProvisionSubnetTaskService.FACTORY_LINK)
                        .setBody(provisionTaskState))
                .thenApply(op -> context);
    }

    private DeferredResult<Context> provisionSecurityGroup(Context context) {

        AssertUtil.assertNotNull(context.isolationSecurityGroup,
                "Context.isolationSecurityGroup should not be null.");
        AssertUtil.assertNotNull(context.subnetNetworkState,
                "Context.subnetNetworkState should not be null.");

        ProvisionSecurityGroupTaskState provisionTaskState = new ProvisionSecurityGroupTaskState();
        provisionTaskState.isMockRequest = DeploymentProfileConfig.getInstance().isTest();
        provisionTaskState.requestType = SecurityGroupInstanceRequest.InstanceRequestType.CREATE;
        provisionTaskState.tenantLinks = context.computeNetwork.tenantLinks;
        provisionTaskState.documentExpirationTimeMicros = ServiceUtils
                .getDefaultTaskExpirationTimeInMicros();
        provisionTaskState.securityGroupDescriptionLinks = Stream.of(context.isolationSecurityGroup
                .documentSelfLink).collect(Collectors.toSet());
        provisionTaskState.serviceTaskCallback = context.serviceTaskCallback;
        provisionTaskState.customProperties = new HashMap<>();
        // the network state id is the vpc id on AWS; it is needed by the photon-model to create
        // this security group
        provisionTaskState.customProperties.put(NETWORK_STATE_ID_PROP_NAME, context
                .subnetNetworkState.id);

        return this.sendWithDeferredResult(
                Operation.createPost(this, ProvisionSecurityGroupTaskService.FACTORY_LINK)
                        .setBody(provisionTaskState))
                .thenApply(op -> context);
    }

    private DeferredResult<Context> configureConnectedResources(Context context) {
        if (context.connectedResources == null || context.connectedResources.isEmpty()) {
            // there are no resources attached to this network
            return DeferredResult.completed(context);
        }

        List<DeferredResult<Operation>> patchOps = context.connectedResources.stream()
                .map(cr -> NetworkProfileQueryUtils
                        .selectSubnet(getHost(), UriUtils.buildUri(getHost(), getSelfLink()),
                                context.state.tenantLinks, context.profile.endpointLink,
                                cr.description.regionId,
                                cr.networkInterfaceDescription, context.profile,
                                context.computeNetwork, context.computeNetworkDescription,
                                context.subnet, false)
                        .thenApply(subnetState -> context.subnet = subnetState)
                        .thenCompose(subnetState -> updateConnectedResourceWithSubnet(context, cr,
                                subnetState)))
                .collect(Collectors.toList());

        return DeferredResult.allOf(patchOps)
                .thenApply(ignore -> context)
                .whenComplete((v, e) -> {
                    if (e != null) {
                        failTask("Failure configuring connected resources", e);
                        return;
                    }
                });
    }

    private DeferredResult<Operation> updateConnectedResourceWithSubnet(Context context,
            ConnectedResource cr, SubnetState subnetState) {
        if (cr.resource instanceof ComputeState) {
            return NetworkProfileQueryUtils
                    .createNicState(subnetState, context.state.tenantLinks,
                            context.profile.endpointLink, (ComputeDescription) cr.description,
                            cr.networkInterfaceDescription, context.isolationSecurityGroup,
                            context.profile.networkProfile.securityGroupLinks)
                    .thenCompose(nic -> this.sendWithDeferredResult(Operation
                            .createPost(this, NetworkInterfaceService.FACTORY_LINK).setBody(nic),
                            NetworkInterfaceState.class))
                    .thenCompose(nis ->
                            patchComputeState((ComputeState) cr.resource, nis.documentSelfLink));
        }

        if (cr.resource instanceof LoadBalancerState) {
            return patchLoadBalancerState(cr.resource.documentSelfLink,
                    subnetState.documentSelfLink);
        }

        throw new IllegalStateException(
                "Unexpected resource type: " + cr.resource.getClass().getCanonicalName());
    }

    private DeferredResult<Context> patchComputeNetwork(Context context) {
        if (context.subnet != null) {
            context.computeNetwork.subnetLink = context.subnet.documentSelfLink;
        }
        if (context.isolationSecurityGroup != null) {
            if (context.computeNetwork.securityGroupLinks == null) {
                context.computeNetwork.securityGroupLinks = new HashSet<>();
            }
            context.computeNetwork.securityGroupLinks.add(context
                    .isolationSecurityGroup.documentSelfLink);
        }
        return this.sendWithDeferredResult(
                Operation.createPatch(this, context.computeNetwork.documentSelfLink)
                        .setBody(context.computeNetwork))
                .thenApply(op -> context);
    }

    private DeferredResult<Operation> patchComputeState(
            ComputeState computeState,
            String networkLink) {

        computeState.networkInterfaceLinks = Arrays.asList(networkLink);
        return this.sendWithDeferredResult(
                Operation.createPatch(this, computeState.documentSelfLink)
                        .setBody(computeState));
    }

    private DeferredResult<Operation> patchLoadBalancerState(String loadBalancerLink,
            String subnetLink) {
        LoadBalancerState patchBody = new LoadBalancerState();
        patchBody.subnetLinks = Collections.singleton(subnetLink);
        return this.sendWithDeferredResult(
                Operation.createPatch(this, loadBalancerLink).setBody(patchBody));
    }

    private List<Rule> buildIsolationRules(String direction) {
        Rule isolationRule = new Rule();
        isolationRule.name = direction + "-deny-all";
        isolationRule.protocol = SecurityGroupService.ANY;
        isolationRule.ipRangeCidr = "0.0.0.0/0";
        isolationRule.access = Access.Deny;
        isolationRule.ports = "1-65535";

        return Arrays.asList(isolationRule);
    }

    private DeferredResult<Context> pupulateSubnetNetworkState(Context context) {
        AssertUtil.assertNotNull(context.subnet,
                "Context.subnet should not be null.");

        return this.sendWithDeferredResult(
                Operation.createGet(this, context.subnet.networkLink), NetworkState.class)
                .thenApply(networkState -> {
                    context.subnetNetworkState = networkState;
                    return context;
                });
    }

    private DeferredResult<Context> populateEndpointComputeState(Context context) {
        AssertUtil.assertNotNull(context.profile.endpoint,
                "Context.profile.endpoint should not be null.");

        return this.sendWithDeferredResult(
                Operation.createGet(ComputeStateWithDescription
                        .buildUri(UriUtils.buildUri(getHost(), context.profile.endpoint
                                .computeLink))),
                ComputeStateWithDescription.class)
                .thenApply(computeState -> {
                    context.endpointComputeState = computeState;
                    return context;
                });
    }

    private <T extends ResourceState> DeferredResult<T> getDocumentDR(String link, Class<T> type) {
        return this.sendWithDeferredResult(Operation.createGet(this, link), type);
    }

    private <T extends ResourceState> DeferredResult<List<T>> getDocumentsDR(List<String> links,
            Class<T> type) {
        if (links == null) {
            return DeferredResult.completed(Collections.emptyList());
        }
        return DeferredResult.allOf(
                links.stream().map(link -> getDocumentDR(link, type)).collect(Collectors.toList()));
    }
}
