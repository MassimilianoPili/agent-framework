package com.agentframework.compiler;

import com.agentframework.compiler.manifest.AgentManifest;
import com.agentframework.compiler.manifest.ManifestLoader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Validates all agent manifest YAML files without generating any output.
 * Useful as a CI gate to catch manifest errors early.
 */
@Mojo(name = "validate-manifests", defaultPhase = LifecyclePhase.VALIDATE)
public class ValidateManifestsMojo extends AbstractMojo {

    @Parameter(property = "agentCompiler.manifestDirectory",
               defaultValue = "${project.basedir}/agents/manifests",
               required = true)
    private String manifestDirectory;

    @Override
    public void execute() throws MojoFailureException {
        Path manifestDir = Path.of(manifestDirectory);
        getLog().info("Agent Compiler: validating manifests in " + manifestDir);

        ManifestLoader loader = new ManifestLoader();
        try {
            List<AgentManifest> manifests = loader.loadAll(manifestDir);
            getLog().info("Agent Compiler: all " + manifests.size() + " manifest(s) are valid.");
        } catch (ManifestLoader.ManifestValidationException e) {
            throw new MojoFailureException("Manifest validation failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoFailureException("Failed to read manifests: " + e.getMessage(), e);
        }
    }
}
