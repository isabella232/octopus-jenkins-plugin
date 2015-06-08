package hudson.plugins.octopusdeploy;
import com.octopusdeploy.api.*;
import hudson.Launcher;
import hudson.Extension;
import hudson.model.*;
import hudson.tasks.*;
import hudson.util.*;
import java.io.IOException;
import java.util.Set;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.*;

/**
 * Executes deployments of releases.
 */
public class OctopusDeployDeploymentRecorder extends Recorder {
    
    /**
     * The Project name of the project as defined in Octopus.
     */
    private final String project;
    public String getProject() {
        return project;
    }
    
    /**
     * The release version number in Octopus.
     */
    private final String releaseVersion;
    public String getReleaseVersion() {
        return releaseVersion;
    }
    
    /**
     * The environment to deploy to in Octopus.
     */
    private final String environment;
    public String getEnvironment() {
        return environment;
    }
    
    @DataBoundConstructor
    public OctopusDeployDeploymentRecorder(String project, String releaseVersion, String environment) {
        this.project = project.trim();
        this.releaseVersion = releaseVersion.trim();
        this.environment = environment.trim();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }
    
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        // This method deserves a refactor and cleanup.
        boolean success = true;
        Log log = new Log(listener);
        log.info("Started Octopus Deploy");
        log.info("======================");
        log.info("Project: " + project);
        log.info("Version: " + releaseVersion);
        log.info("Environment: " + environment);
        log.info("======================");
        ((DescriptorImpl)getDescriptor()).setGlobalConfiguration();
        OctopusApi api = new OctopusApi(((DescriptorImpl)getDescriptor()).octopusHost, ((DescriptorImpl)getDescriptor()).apiKey);
        
        com.octopusdeploy.api.Project p = null;
        try {
            p = api.getProjectByName(project);
        } catch (Exception ex) {
            log.fatal(String.format("Retrieving project name '%s' failed with message '%s'",
                    project, ex.getMessage()));
            success = false;
        }
        com.octopusdeploy.api.Environment env = null;
        try {
            env = api.getEnvironmentByName(environment);
        } catch (Exception ex) {
            log.fatal(String.format("Retrieving environment name '%s' failed with message '%s'",
                    environment, ex.getMessage()));
            success = false;
        }
        if (p == null) {
            log.fatal("Project was not found.");
            success = false;
        }
        if (env == null) {
            log.fatal("Environment was not found.");
            success = false;
        }
        if (!success) // Early exit
        {
            return success;
        }
        Set<com.octopusdeploy.api.Release> releases = null;
        try {
            releases = api.getReleasesForProject(p.getId());
        } catch (Exception ex) {
            log.fatal(String.format("Retrieving releases for project '%s' failed with message '%s'",
                    project, ex.getMessage()));
            success = false;
        }
        if (releases == null) {
            log.fatal("Releases was not found.");
            return false;
        }
        Release releaseToDeploy = null;
        for(Release r : releases) {
            if (releaseVersion.equals(r.getVersion()))
            {
                releaseToDeploy = r;
                break;
            }
        }
        if (releaseToDeploy == null) // early exit
        {
            log.fatal(String.format("Unable to find release version %s for project %s", releaseVersion, project));
            return false;
        }
        try {
            String results = api.executeDeployment(releaseToDeploy.getId(), env.getId());
            log.info(results);
        } catch(IOException ex) {
            log.fatal("Failed to deploy: " + ex.getMessage());
            success = false;
        }
        
        return success;
    }

    /**
     * Descriptor for {@link OctopusDeployDeploymentRecorder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private String octopusHost;
        private String apiKey;
        private boolean loadedConfig;
        
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "OctopusDeploy Deployment";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws Descriptor.FormException {
            save();
            return super.configure(req, formData);
        }
        
        /**
         * Allows plugin user to validate release information by implementing Validate button.
         * @param project
         * @param releaseVersion
         * @param environment 
         * @return A FormValidation object with the validation status and a brief message explaining the status. 
         */
        public FormValidation doDeployValidation(@QueryParameter("project") final String project,
            @QueryParameter("releaseVersion") final String releaseVersion,
            @QueryParameter("environment") final String environment) {
            // Tests go here, then return one of the following based on results:
             return FormValidation.ok("This is a Success message");
            // return FormValidation.ok("This is a Warning message");
            // return FormValidation.ok("This is a Error message");
        }
        
        /**
        * Loads the OctopusDeployPlugin descriptor and pulls configuration from it
        * for API Key, and Host.
        */
        private void setGlobalConfiguration() {
            // NOTE  - This method is not being called from the constructor due 
            // to a circular dependency issue on startup
            if (!loadedConfig) { 
                OctopusDeployPlugin.DescriptorImpl descriptor = (OctopusDeployPlugin.DescriptorImpl) 
                       Jenkins.getInstance().getDescriptor(OctopusDeployPlugin.class );
                apiKey = descriptor.getApiKey();
                octopusHost = descriptor.getOctopusHost();
                loadedConfig = true;
            }
        }
        
        /**
         * Check that the project field is not empty and is a valid project.
         * @param project The name of the project.
         * @return Ok if not empty, error otherwise.
         */
        public FormValidation doCheckProject(@QueryParameter String project) {
            setGlobalConfiguration(); 
            project = project.trim(); // TODO: Extract this to be shared between plugins
            if (project.isEmpty()) {
                return FormValidation.error("Please provide a project name.");
            }
            OctopusApi api = new OctopusApi(octopusHost, apiKey);
            try {
                com.octopusdeploy.api.Project p = api.getProjectByName(project, true);
                if (p == null)
                {
                    return FormValidation.error("Project not found.");
                }
                if (!project.equals(p.getName()))
                {
                    return FormValidation.warning("Project name case does not match. Did you mean '%s'?", p.getName());
                }
            } catch (IllegalArgumentException ex) {
                return FormValidation.error(ex.getMessage());
            } catch (IOException ex) {
                return FormValidation.error(ex.getMessage());
            }
            return FormValidation.ok();
        }
        
        /**
         * Check that the releaseVersion field is not empty.
         * @param releaseVersion The release version of the package.
         * @return Ok if not empty, error otherwise.
         */
        public FormValidation doCheckReleaseVersion(@QueryParameter String releaseVersion) {
            if ("".equals(releaseVersion)) {
                return FormValidation.error("Please provide a release version.");
            }
            return FormValidation.ok();
        }
        
        /**
         * Check that the environment field is not empty.
         * @param environment The name of the project.
         * @return Ok if not empty, error otherwise.
         */
        public FormValidation doCheckEnvironment(@QueryParameter String environment) {
            setGlobalConfiguration();
            // TODO: Extract this to be shared between plugins
            // TODO: Deduplicate this with project check
            environment = environment.trim(); 
            if (environment.isEmpty()) {
                return FormValidation.error("Please provide an environment name.");
            }
            OctopusApi api = new OctopusApi(octopusHost, apiKey);
            try {
                com.octopusdeploy.api.Environment env = api.getEnvironmentByName(environment, true);
                if (env == null)
                {
                    return FormValidation.error("Environment not found.");
                }
                if (!environment.equals(env.getName()))
                {
                    return FormValidation.warning("Environment name case does not match. Did you mean '%s'?", env.getName());
                }
            } catch (IllegalArgumentException ex) {
                return FormValidation.error(ex.getMessage());
            } catch (IOException ex) {
                return FormValidation.error(ex.getMessage());
            }
            return FormValidation.ok();
        }
    }
}
