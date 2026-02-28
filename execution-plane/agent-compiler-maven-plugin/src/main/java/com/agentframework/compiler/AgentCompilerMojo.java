package com.agentframework.compiler;

import com.agentframework.compiler.generator.WorkerGenerator;
import com.agentframework.compiler.manifest.AgentManifest;
import com.agentframework.compiler.manifest.ManifestLoader;
import com.agentframework.compiler.registry.McpRegistryLoader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Maven plugin goal that generates worker implementations from agent manifest YAML files.
 *
 * <p>Reads all {@code *.agent.yml} files from the manifest directory, validates them,
 * and generates complete Maven modules (Java source, application.yml, pom.xml) for each.</p>
 *
 * <p>Usage in pom.xml:</p>
 * <pre>{@code
 * <plugin>
 *     <groupId>com.agentframework</groupId>
 *     <artifactId>agent-compiler-maven-plugin</artifactId>
 *     <configuration>
 *         <manifestDirectory>${project.basedir}/agents/manifests</manifestDirectory>
 *         <outputDirectory>${project.basedir}/execution-plane/workers</outputDirectory>
 *     </configuration>
 *     <executions>
 *         <execution>
 *             <goals><goal>generate-workers</goal></goals>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "generate-workers", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class AgentCompilerMojo extends AbstractMojo {

    /**
     * Directory containing {@code *.agent.yml} manifest files.
     */
    @Parameter(property = "agentCompiler.manifestDirectory",
               defaultValue = "${project.basedir}/agents/manifests",
               required = true)
    private String manifestDirectory;

    /**
     * Output directory for generated worker modules.
     */
    @Parameter(property = "agentCompiler.outputDirectory",
               defaultValue = "${project.basedir}/execution-plane/workers",
               required = true)
    private String outputDirectory;

    /**
     * Path to the MCP server registry YAML file.
     * Used to resolve connection details for MCP servers declared in agent manifests.
     */
    @Parameter(property = "agentCompiler.mcpRegistryFile",
               defaultValue = "${project.basedir}/mcp/registry/mcp-registry.yml")
    private String mcpRegistryFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path manifestDir = Path.of(manifestDirectory);
        Path outputDir = Path.of(outputDirectory);
        Path registryPath = Path.of(mcpRegistryFile);

        getLog().info("Agent Compiler: reading manifests from " + manifestDir);
        getLog().info("Agent Compiler: generating workers to " + outputDir);

        // Load MCP registry (optional — missing file means no MCP client generation)
        McpRegistryLoader mcpRegistry = null;
        if (Files.exists(registryPath)) {
            try {
                mcpRegistry = McpRegistryLoader.load(registryPath);
                getLog().info("Agent Compiler: loaded MCP registry from " + registryPath
                        + " (" + mcpRegistry.all().size() + " server(s))");
            } catch (IOException e) {
                getLog().warn("Agent Compiler: failed to load MCP registry, "
                        + "MCP client config will not be generated: " + e.getMessage());
            }
        }

        ManifestLoader loader = new ManifestLoader();
        WorkerGenerator generator = new WorkerGenerator(mcpRegistry);

        try {
            List<AgentManifest> manifests = loader.loadAll(manifestDir);
            getLog().info("Agent Compiler: found " + manifests.size() + " agent manifest(s)");

            for (AgentManifest manifest : manifests) {
                String name = manifest.getMetadata().getName();
                String fileName = name.replace("-worker", "") + ".agent.yml";

                Path moduleDir = generator.generate(manifest, outputDir, fileName);
                getLog().info("  Generated: " + name + " -> " + moduleDir);
            }

            getLog().info("Agent Compiler: generation complete. "
                + manifests.size() + " worker module(s) generated.");

        } catch (ManifestLoader.ManifestValidationException e) {
            throw new MojoFailureException("Manifest validation failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read manifests or write output: " + e.getMessage(), e);
        }
    }
}
