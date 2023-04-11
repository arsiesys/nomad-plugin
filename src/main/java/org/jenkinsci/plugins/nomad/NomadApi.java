package org.jenkinsci.plugins.nomad;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.nomad.Api.JobInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;

import hudson.util.FormValidation;
import hudson.util.Secret;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Provides access to Nomad by using the Nomad REST API.
 */
public final class NomadApi {

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Logger LOGGER = Logger.getLogger(NomadApi.class.getName());
    private final NomadCloud cloud;
    private volatile OkHttpClient client;

    NomadApi(NomadCloud cloud) {
        this.cloud = cloud;
    }

    /**
     * Checks whether Nomad is reachable.
     * @return FormValidation object with kind = OK or ERROR and a message.
     */
    public FormValidation checkConnection() {
        Request request = createRequestBuilder("/v1/agent/self", null)
                .build();

        try (Response response = executeRequest(request)) {
            if (response.isSuccessful()) {
                return FormValidation.ok("Nomad API request succeeded.");
            }
            try (ResponseBody body = response.body()) {
                String message = null;
                if (body != null) {
                    message = body.string();
                }
                return FormValidation.error(StringUtils.isEmpty(message) ? response.toString() : message);
            }
        } catch (Exception e) {
            return FormValidation.error(e.getMessage());
        }
    }

    /**
     * Validates a given {@link NomadWorkerTemplate}.
     * @return FormValidation object with kind = OK or ERROR and a message.
     */
    public FormValidation validateTemplate(NomadWorkerTemplate template) {
        String id = UUID.randomUUID().toString();

        Request request = createRequestBuilder("/v1/job/" + id + "/plan", null)
                .post(RequestBody.create(buildWorkerJob(id, "", template), JSON))
                .build();

        try (Response response = executeRequest(request)) {
            if (response.isSuccessful()) {
                return FormValidation.ok("OK");
            }
            try (ResponseBody body = response.body()) {
                return FormValidation.error(body != null ? body.string() : response.toString());
            }
        } catch (IOException e) {
            return FormValidation.error(e.getMessage());
        }
    }
    
    /**
     * Verify Nomad allocation availability for a template
     * @return true if the plan confirm that nomad is able to allocate the job
     */
    public boolean checkAllocAvailability(NomadWorkerTemplate template) {
        String id = UUID.randomUUID().toString();

        Request request = createRequestBuilder("/v1/job/" + id + "/plan", null)
                .post(RequestBody.create(buildWorkerJob(id, "", template), JSON))
                .build();

        try (Response response = executeRequest(request)) {
            if (!response.isSuccessful()) {
                return false;
            }
            try (ResponseBody body = response.body()) {
            	JSONObject bodyJson = new JSONObject(body.string());
            	if (bodyJson.has("FailedTGAllocs") && bodyJson.isNull("FailedTGAllocs")) {
            		return true;
            	}
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /**
     * Creates a new job in Nomad. It logs when it was not successful but there is no further indication whether this was successful or not.
     * @param workerName Name of the corresponding {@link NomadWorker} (e.g. jenkins-1234)
     * @param jnlpSecret Secret used by the jenkins agent to connect to Jenkins
     * @param template Template used to create a new Job in Nomad
     */
    public String startWorker(String workerName, String jnlpSecret, NomadWorkerTemplate template) {

        String workerJob = buildWorkerJob(
                workerName,
                jnlpSecret,
                template
        );

        LOGGER.log(Level.FINE, workerJob);

        Request request = createRequestBuilder("/v1/jobs", null)
                .put(RequestBody.create(workerJob, JSON))
                .build();

        checkResponseAndGetBody(request);
        return workerJob;
    }

    /**
     * Deletes an existing job in Nomad. It logs when it was not successful but there is no further indication whether this was successful
     * or not.
     * @param workerName Name of the corresponding {@link NomadWorker} (e.g. jenkins-1234)
     * @param namespace Name of the nomad namespace where job is running
     * @param region Name of the region where job is running
     */
    public void stopWorker(String workerName, String namespace, String region) {
        Map<String,String> params = new HashMap<>();
        if (namespace != null)
            params.put("namespace", namespace);
        if (region != null && !region.equals("global"))
            params.put("region", region);

        Request request = createRequestBuilder("/v1/job/" + workerName, params)
                .delete()
                .build();

        checkResponseAndGetBody(request);
    }

    /**
     * Provides a lists all existing jobs in Nomad with the same prefix. It logs when it was not successful but there is no further
     * indication whether this was successful or not.
     * @param prefix Prefix of the job (e.g.jenkins when you want all jobs where the name starts with jenkins)
     * @return Array of {@link JobInfo} objects or an empty list if there are no Jobs at all or when something was wrong
     */
    public JobInfo[] getRunningWorkers(String prefix) {
        Map<String,String> params = new HashMap<>();
        params.put("namespace", "*");
        params.put("prefix", prefix);

        Request request = createRequestBuilder("/v1/jobs", params)
                .get()
                .build();
        String body = checkResponseAndGetBody(request);
        return new Gson().fromJson(body, JobInfo[].class);
    }

    /**
     * Get all job specifications and status
     * @param jobID Id of the job
     * @param namespace Name of the nomad namespace where job is running
     * @return {@link JSONObject} object
     */
    public JSONObject getRunningWorker(String jobID, String namespace) {
        Map<String,String> params = new HashMap<>();
        if (namespace != null)
            params.put("namespace", namespace);
        Request request = createRequestBuilder("/v1/job/" + jobID, params)
                .get()
                .build();
        String body = checkResponseAndGetBody(request);
        return new JSONObject(body);
    }

    /**
     * Creates from a given job template a Nomad Job which can be sent to Nomad.
     * @param name Name of the Nomad Job (e.g. jenkins-1234)
     * @param secret Secret used by the jenkins agent to connect to Jenkins
     * @param template Template used to create a new Job in Nomad
     * @return Nomad Job in JSON format which is can be sent to Nomad via the /v1/jobs REST API
     */
    String buildWorkerJob(
            String name,
            String secret,
            NomadWorkerTemplate template
    ) {

        String job = normalizeJobTemplate(template.getJobTemplate())
                .replace("%WORKER_NAME%", name)
                .replace("%WORKER_SECRET%", secret)
                .replace("%WORKER_DIR%", template.getRemoteFs());

        LOGGER.log(Level.FINE, String.format("job:%n%s", job));
        return job;
    }

    /**
     * Converts a given job template to the Nomad REST API compliant job format.
     * @param jobTemplate Nomad-Job (HCL or JSON)
     * @return the given job template (converted to JSON if necessary)
     */
    private String normalizeJobTemplate(String jobTemplate) {
        if (!isJSON(jobTemplate)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

            JsonObject jobHCL = new JsonObject();
            jobHCL.addProperty("JobHCL", jobTemplate);

            Request request = createRequestBuilder("/v1/jobs/parse", null)
                    .post(RequestBody.create(gson.toJson(jobHCL), JSON))
                    .build();

            try (Response response = executeRequest(request)) {
                ResponseBody body = response.body();
                if (body != null) {
                    JsonObject jobJson = new JsonObject();
                    jobJson.add("Job", gson.fromJson(body.string(), JsonObject.class));
                    body.close();
                    return gson.toJson(jobJson);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Converting job from HCL to JSON failed!", e);
            }
        }

        return jobTemplate;
    }

    /**
     * Returns true if the given String is a valid JSON document.
     */
    private boolean isJSON(String source) {
        try {
            new JSONObject(source);
        } catch (JSONException ex) {
            try {
                new JSONArray(source);
            } catch (JSONException ex1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Executes a given request and returns the response body. It logs when it was not successful but there is no further indication
     * for the callee whether this was successful or not.
     * @param request Any request (not null)
     * @return Response body as String or an empty String. (not null)
     * @deprecated use {@link #executeRequest(Request)}
     */
    private String checkResponseAndGetBody (Request request) {
        String bodyString = "";
        try (Response response = executeRequest(request);
             ResponseBody body = response.body()
        ) {
            if (body != null) {
                bodyString = body.string();
            }

            if (!response.isSuccessful()) {
                LOGGER.log(Level.SEVERE, "Request was not successful! Code: "+response.code()+", Body: '"+bodyString+"'"+"URL: "+request.url());
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage() + "\nRequest:\n" + request);
        }
        return bodyString;
    }

    /**
     * Executes a given request, returns the response and takes care of the underlying client. Note: It is up the callee to close the
     * {@link Response}.
     * @param request given request
     * @return response (not null)
     * @throws IOException if the request could not be executed
     * @see Call#execute()
     */
    private Response executeRequest(Request request) throws IOException {
        try {
            Response response = client().newCall(request).execute();
            if (!response.isSuccessful() && Arrays.asList(401, 403, 500).contains(response.code())) {
                resetClient();
            }
            return response;
        } catch (IOException e) {
            resetClient();
            throw e;
        }
    }

    /**
     * Provides a new request builder with a fresh Nomad token (if necessary).
     * @param path Relative path to a Nomad resource (e.g. /v1/agent/self)
     */
    private Request.Builder createRequestBuilder(String path, Map<String,String> params) {
        Request.Builder builder = new Request.Builder();
        String nomadUrl = cloud.getNomadUrl();
        if (nomadUrl == null || path == null)
            throw new RuntimeException("Unable to send request to Nomad as nomad url/path is empty");
        HttpUrl httpUrl = HttpUrl.parse(nomadUrl+path);
        if (httpUrl == null)
            throw new RuntimeException("Unable to parse Nomad url");
        HttpUrl.Builder httpBuilder = httpUrl.newBuilder();
        if (params != null) {
           for(Map.Entry<String, String> param : params.entrySet()) {
               httpBuilder.addQueryParameter(param.getKey(),param.getValue());
           }
        }
        builder.url(httpBuilder.build());
        String nomadToken = cloud.getNomadACL();
        if (StringUtils.isNotEmpty(nomadToken)) {
            builder = builder.addHeader("X-Nomad-Token", nomadToken);
        }

        return builder;
    }

    /**
     * Reset client so that a new client gets created when {@link #client()} is called.
     */
    private void resetClient() {
        // The fact that a certificate can expire, requires that the client can be recreated at runtime (Assumption: client certificate
        // is renewed somehow but the path is still the same).
        client = null;
        LOGGER.log(Level.INFO, "Client has been reset!");
    }

    /**
     * Provides an {@link OkHttpClient} instance. Create a new client or reuse the existing one. This method is thread-safe.
     * @return OkHttpClient instance (not null but TLS might not work)
     */
    private OkHttpClient client() {
        // We cannot use a static client instance because TLS must be configured when the client gets created.
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
                    if (cloud.isTlsEnabled()) {
                        try {
                            OkHttpClientHelper.initTLS(clientBuilder,
                                    cloud.getClientCertificate(), Secret.toString(cloud.getClientPassword()),
                                    cloud.getServerCertificate(), Secret.toString(cloud.getServerPassword()));
                        } catch (GeneralSecurityException | IOException e) {
                            LOGGER.log(Level.SEVERE, "Nomad TLS configuration failed! " + e.getMessage());
                        }
                    }
                    client = clientBuilder.build();
                }
            }
        }

        return client;
    }

}
