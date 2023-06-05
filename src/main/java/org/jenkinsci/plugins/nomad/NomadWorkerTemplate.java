package org.jenkinsci.plugins.nomad;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;

public class NomadWorkerTemplate implements Describable<NomadWorkerTemplate> {

    private static final String SLAVE_PREFIX = "jenkins";

    // persistent fields
    private final String prefix;
    private final int maxConcurrentJobs;
    private final int idleTerminationInMinutes;
    private final boolean reusable;
    private final int numExecutors;
    private final String labels;
    private final String jobTemplate;
    private final String remoteFs;

    // legacy fields (we have to keep them for backward compatibility)
    @Deprecated
    private transient String region;
    @Deprecated
    private transient int cpu;
    @Deprecated
    private transient int memory;
    @Deprecated
    private transient int disk;
    @Deprecated
    private transient int priority;
    @Deprecated
    private transient List<? extends NomadConstraintTemplate> constraints;
    @Deprecated
    private transient Boolean useRawExec;
    @Deprecated
    private transient String image;
    @Deprecated
    private transient Boolean privileged;
    @Deprecated
    private transient String network;
    @Deprecated
    private transient String username;
    @Deprecated
    private transient Secret password;
    @Deprecated
    private transient String prefixCmd;
    @Deprecated
    private transient Boolean forcePull;
    @Deprecated
    private transient String hostVolumes;
    @Deprecated
    private transient String switchUser;
    @Deprecated
    private transient Node.Mode mode;
    @Deprecated
    private transient List<? extends NomadPortTemplate> ports;
    @Deprecated
    private transient String extraHosts;
    @Deprecated
    private transient String dnsServers;
    @Deprecated
    private transient String securityOpt;
    @Deprecated
    private transient String capAdd;
    @Deprecated
    private transient String capDrop;
    @Deprecated
    private transient String datacenters;
    @Deprecated
    private transient String vaultPolicies;
    @Deprecated
    private transient Set<LabelAtom> labelSet;
    @Deprecated
    private transient List<? extends NomadDevicePluginTemplate> devicePlugins;
    @Deprecated
    private transient String driver;

    @DataBoundConstructor
    public NomadWorkerTemplate(
            String prefix,
            String labels,
            int maxConcurrentJobs,
            int idleTerminationInMinutes,
            boolean reusable,
            int numExecutors,
            String remoteFs,
            String jobTemplate
    ) {
        this.prefix = prefix.isEmpty() ? SLAVE_PREFIX : prefix;
        this.maxConcurrentJobs = maxConcurrentJobs;
        this.idleTerminationInMinutes = idleTerminationInMinutes;
        this.reusable = reusable;
        this.numExecutors = numExecutors;
        this.labels = Util.fixNull(labels);
        this.remoteFs = Util.fixNull(remoteFs);
        this.jobTemplate = jobTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<NomadWorkerTemplate> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }

    public String createWorkerName() {
        return prefix + "-" + Long.toHexString(System.nanoTime());
    }

    public String getPrefix() {
        return prefix;
    }

    public int getMaxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }

    public boolean isReusable() {
        return reusable;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public String getLabels() {
        return labels;
    }

    public String getRemoteFs() {
        return remoteFs;
    }

    public String getJobTemplate() {
        return jobTemplate;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<NomadWorkerTemplate> {
        public static final String defaultJobTemplate = loadDefaultJobTemplate();

        private static String loadDefaultJobTemplate() {
            try {
                return IOUtils.toString(DescriptorImpl.class.getResource("/org/jenkinsci/plugins/nomad/jobTemplate.json"), UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "";
        }

        @POST
        public FormValidation doValidation(
                @QueryParameter String nomadUrl,
                @QueryParameter boolean tlsEnabled,
                @QueryParameter String clientCertificate,
                @QueryParameter String clientPassword,
                @QueryParameter String serverCertificate,
                @QueryParameter String serverPassword,
                @QueryParameter String nomadACLCredentialsId,
                @QueryParameter String jobTemplate) {
            Objects.requireNonNull(Jenkins.get()).checkPermission(Jenkins.ADMINISTER);

            NomadCloud cloud = new NomadCloud(
                    "validate-template-" + UUID.randomUUID(),
                    nomadUrl,
                    tlsEnabled,
                    clientCertificate,
                    Secret.fromString(clientPassword),
                    serverCertificate,
                    Secret.fromString(serverPassword),
                    1,
                    nomadACLCredentialsId,
                    false,
                    null
            );

            NomadWorkerTemplate template = new NomadWorkerTemplate(
                    "validate-template",
                    null,
                    -1,
                    0,
                    false,
                    1,
                    null,
                    jobTemplate
            );

            NomadApi api = new NomadApi(cloud);
            return api.validateTemplate(template);
        }
    }
}
