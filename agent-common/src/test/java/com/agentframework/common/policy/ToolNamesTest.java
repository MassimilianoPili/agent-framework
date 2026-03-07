package com.agentframework.common.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ToolNames} — constants, categories, and query methods.
 */
class ToolNamesTest {

    @Test
    void constants_matchExpectedToolNames() {
        assertThat(ToolNames.FS_LIST).isEqualTo("fs_list");
        assertThat(ToolNames.FS_READ).isEqualTo("fs_read");
        assertThat(ToolNames.FS_WRITE).isEqualTo("fs_write");
        assertThat(ToolNames.FS_SEARCH).isEqualTo("fs_search");
        assertThat(ToolNames.FS_GREP).isEqualTo("fs_grep");
    }

    @Test
    void categories_containCorrectTools() {
        assertThat(ToolNames.WRITE_TOOLS).containsExactly("fs_write", "bash_execute", "python_execute");
        assertThat(ToolNames.READ_TOOLS).containsExactly("fs_read", "fs_grep");
        assertThat(ToolNames.ALL_FS_TOOLS).containsExactly(
                "fs_list", "fs_read", "fs_write", "fs_search", "fs_grep");
        assertThat(ToolNames.READONLY_FS_TOOLS).containsExactly(
                "fs_list", "fs_read", "fs_search", "fs_grep");
        // READONLY should be ALL minus WRITE
        assertThat(ToolNames.READONLY_FS_TOOLS).doesNotContain("fs_write");
    }

    @Test
    void isWriteTool_andIsReadTool_classifyCorrectly() {
        assertThat(ToolNames.isWriteTool("fs_write")).isTrue();
        assertThat(ToolNames.isWriteTool("bash_execute")).isTrue();
        assertThat(ToolNames.isWriteTool("python_execute")).isTrue();
        assertThat(ToolNames.isWriteTool("fs_read")).isFalse();
        assertThat(ToolNames.isWriteTool("fs_list")).isFalse();
        assertThat(ToolNames.isWriteTool("unknown_tool")).isFalse();

        assertThat(ToolNames.isReadTool("fs_read")).isTrue();
        assertThat(ToolNames.isReadTool("fs_grep")).isTrue();
        assertThat(ToolNames.isReadTool("fs_write")).isFalse();
        assertThat(ToolNames.isReadTool("fs_list")).isFalse();
    }
}
