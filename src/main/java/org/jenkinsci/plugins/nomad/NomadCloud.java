package org.jenkinsci.plugins.nomad;

import static org.apache.commons.lang.StringUtils.trimToEmpty;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.filter;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.withId;
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.nomad.Api.JobInfo;
import org.jenkinsci.plugins.nomad.Api.JobSummary;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.google.common.base.Strings;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpAgentReceiver;

public class NomadCloud extends AbstractCloudImpl {

    private static final Logger LOGGER = Logger.getLogger(NomadCloud.class.getName());

    // persistent fields
    private final String nomadUrl;
    private final String nomadACLCredentialsId;
    private final boolean prune;
    private final boolean tlsEnabled;
    private final String clientCertificate;
    private final Secret clientPassword;
    private final String serverCertificate;
    private final Secret serverPassword;
    private final int workerTimeout;
    private final List<NomadWorkerTemplate> templates;

    // non persistent fields
    private transient NomadApi nomad;
    private transient int pending = 0;

    // legacy fields (we have to keep them for backward compatibility)
    private transient String jenkinsUrl;
    private transient String jenkinsTunnel;
    private transient String workerUrl;

    @DataBoundConstructor
    public NomadCloud(
            String name,
            String nomadUrl,
            boolean tlsEnabled,
            String clientCertificate,
            Secret clientPassword,
            String serverCertificate,
            Secret serverPassword,
            int workerTimeout,
            String nomadACLCredentialsId,
            boolean prune,
            List<NomadWorkerTemplate> templates) {
        super(name, null);

        this.nomadACLCredentialsId = nomadACLCredentialsId;
        this.nomadUrl = nomadUrl;
        this.workerTimeout = workerTimeout;
        this.tlsEnabled = tlsEnabled;
        this.clientCertificate = clientCertificate;
        this.clientPassword = clientPassword;
        this.serverCertificate = serverCertificate;
        this.serverPassword = serverPassword;
        this.prune = prune;
        this.templates = Optional.ofNullable(templates).orElse(new ArrayList<>());

        readResolve();
    }

    private static String secretFor(String credentialsId) {
        List<StringCredentials> creds = filter(
                lookupCredentials(StringCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        Collections.emptyList()),
                withId(trimToEmpty(credentialsId))
        );
        if (creds.size() > 0) {
            return creds.get(0).getSecret().getPlainText();
        } else {
            return null;
        }
    }

    private Object readResolve() {
        nomad = new NomadApi(this);
        MigrationHelper.migrate(this);
        return this;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {

        List<NodeProvisioner.PlannedNode> nodes = new ArrayList<>();
        final NomadWorkerTemplate template = getTemplate(label);

        if (template != null) {
            if (prune)
                pruneOrphanedWorkers(template);

            try {
                while (excessWorkload > 0) {
                    LOGGER.log(Level.INFO, "Excess workload of " + excessWorkload + ", provisioning new Jenkins worker on Nomad cluster");

                    final String workerName = template.createWorkerName();
                    nodes.add(new NodeProvisioner.PlannedNode(
                            workerName,
                            NomadComputer.threadPoolForRemoting.submit(
                                    new ProvisioningCallback(workerName, template, this)
                            ), template.getNumExecutors()));
                    excessWorkload -= template.getNumExecutors();
                    pending += template.getNumExecutors();
                }
                return nodes;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unable to schedule new Jenkins worker on Nomad cluster, message: " + e.getMessage());
            }
        }

        return Collections.emptyList();
    }


    /**
     * Determines if some nomad worker needs to be stopped.
     * A nomad job can be stopped if there is no related jenkins agent running.
     * Make sure that we leave enough time for new worker to connect to Jenkins before removal.
     *
     * @param template - the {@link NomadWorkerTemplate} that was used to start workers.
     */
    private void pruneOrphanedWorkers(NomadWorkerTemplate template) {
        JobInfo[] nomadWorkers = this.nomad.getRunningWorkers(template.getPrefix());

        for (JobInfo worker : nomadWorkers) {
            if (worker.getStatus().equalsIgnoreCase("running")) {
                LOGGER.log(Level.FINE, "Found worker: " + worker.getName() + " - " + worker.getID());
                Node node = Jenkins.get().getNode(worker.getName());

                if (node == null) {
                    JobSummary jobSummary = worker.getJobSummary();
                    String jobNamespace = worker.getJobSummary().getNamespace();
                    JSONObject job = this.nomad.getRunningWorker(jobSummary.getJobID(), jobNamespace);
                    String jobRegion = job.getString("Region");
                    Instant expiryTime = Instant.EPOCH
                        .plusNanos(job.getLong("SubmitTime"))
                        .plus(workerTimeout, ChronoUnit.MINUTES);
                    Instant now = Instant.now();
                    if (now.isAfter(expiryTime)) {
                        LOGGER.log(Level.FINE, "Found Orphaned Node: " + worker.getID() + " in namespace " + jobNamespace + " in region " + jobRegion);
                        this.nomad.stopWorker(worker.getID(), jobNamespace, jobRegion);
                    }
                }
            }
        }

    }

    // Find the correct template for job
    public NomadWorkerTemplate getTemplate(Label label) {
        for (NomadWorkerTemplate t : templates) {
            if (label == null && !t.getLabels().isEmpty()) {
                continue;
            }
            if ((label == null && t.getLabels().isEmpty()) || (label != null && label.matches(Label.parse(t.getLabels())))) {
                return t;
            }
        }
        return null;
    }

    @Override
    public boolean canProvision(Label label) {
        return Optional.ofNullable(getTemplate(label)).isPresent();
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getNomadUrl() {
        return nomadUrl;
    }

    public int getWorkerTimeout() {
        return workerTimeout;
    }

    public String getNomadACLCredentialsId() {
        return nomadACLCredentialsId;
    }

    public String getNomadACL() {
        return secretFor(this.getNomadACLCredentialsId());
    }

    public boolean isPrune() {
        return prune;
    }

    public List<NomadWorkerTemplate> getTemplates() {
        return templates;
    }

    public void setNomad(NomadApi nomad) {
        this.nomad = nomad;
    }

    public int getPending() {
        return pending;
    }

    public NomadApi nomad() {
        return nomad;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public String getClientCertificate() {
        return clientCertificate;
    }

    public Secret getClientPassword() {
        return clientPassword;
    }

    public String getServerCertificate() {
        return serverCertificate;
    }

    public Secret getServerPassword() {
        return serverPassword;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Nomad";
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String nomadUrl,
                @QueryParameter boolean tlsEnabled,
                @QueryParameter String clientCertificate,
                @QueryParameter String clientPassword,
                @QueryParameter String serverCertificate,
                @QueryParameter String serverPassword,
                @QueryParameter String nomadACLCredentialsId) {
            Objects.requireNonNull(Jenkins.get()).checkPermission(Jenkins.ADMINISTER);

            NomadCloud cloud = new NomadCloud(
                    "check-connection-" + UUID.randomUUID(),
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

            NomadApi nomadApi = new NomadApi(cloud);
            return nomadApi.checkConnection();
        }

        @POST
        public FormValidation doCheckName(@QueryParameter String name) {
            Objects.requireNonNull(Jenkins.get()).checkPermission(Jenkins.ADMINISTER);
            if (Strings.isNullOrEmpty(name)) {
                return FormValidation.error("Name must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public ListBoxModel doFillNomadACLCredentialsIdItems(@QueryParameter("nomadACLCredentialsId") String credentialsId) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            CredentialsMatchers.always(),
                            CredentialsProvider.lookupCredentials(StringCredentials.class,
                                    Jenkins.get(),
                                    ACL.SYSTEM,
                                    Collections.emptyList()));
        }
    }

    private class ProvisioningCallback implements Callable<Node> {

        String workerName;
        NomadWorkerTemplate template;
        NomadCloud cloud;

        public ProvisioningCallback(String workerName, NomadWorkerTemplate template, NomadCloud cloud) {
            this.workerName = workerName;
            this.template = template;
            this.cloud = cloud;
        }

        public Node call() throws Exception {
            final NomadWorker worker = new NomadWorker(
                    workerName,
                    name,
                    template.getLabels(),
                    template.getNumExecutors(),
                    template.getIdleTerminationInMinutes(),
                    template.isReusable(),
                    template.getRemoteFs()
            );
            Jenkins.get().addNode(worker);

            String jnlpSecret = JnlpAgentReceiver.SLAVE_SECRET.mac(workerName);

            LOGGER.log(Level.INFO, "Asking Nomad to schedule new Jenkins worker");

            String workerJob = nomad.startWorker(workerName, jnlpSecret, template);
            JSONObject workerJobJSON = new JSONObject(workerJob).getJSONObject("Job");
            String namespace = workerJobJSON.optString("Namespace");
            if (!namespace.equals("")) {
                   worker.setNamespace(namespace);
            }
            worker.setRegion(workerJobJSON.optString("Region"));

            // Check scheduling success
            Callable<Boolean> callableTask = () -> {
                try {
                    LOGGER.log(Level.INFO, "Worker scheduled, waiting for connection");
                    Objects.requireNonNull(worker.toComputer()).waitUntilOnline();
                } catch (InterruptedException e) {
                    LOGGER.log(Level.SEVERE, "Waiting for connection was interrupted");
                    return false;
                }
                return true;
            };

            // Schedule a worker and wait for the computer to come online
            ExecutorService executorService = Executors.newCachedThreadPool();
            Future<Boolean> future = executorService.submit(callableTask);

            try {
                future.get(cloud.workerTimeout, TimeUnit.MINUTES);
                LOGGER.log(Level.INFO, "Connection established");
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Worker computer did not come online within " + workerTimeout + " minutes, terminating worker" + worker);
                worker.terminate();
                throw new RuntimeException("Timed out waiting for agent to start up. Timeout: " + workerTimeout + " minutes.");
            } finally {
                future.cancel(true);
                executorService.shutdown();
                pending -= template.getNumExecutors();
            }
            return worker;
        }
    }
}
