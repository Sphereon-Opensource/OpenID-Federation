// This script runs during Gradle initialization
gradle.projectsLoaded {
    rootProject.afterEvaluate {
        // Check if .git directory exists (we're in a Git repo)
        if (file(".git").exists()) {
            // Create .githooks directory if it doesn't exist
            file(".githooks").mkdirs()

            // Copy the pre-commit hook if it exists
            val preCommitHook = file(".githooks/pre-commit")
            if (preCommitHook.exists()) {
                // Copy hook to .git/hooks
                file(".git/hooks").mkdirs()
                preCommitHook.copyTo(file(".git/hooks/pre-commit"), overwrite = true)

                // Make the hook executable on Unix-like systems
                if (!System.getProperty("os.name").lowercase().contains("windows")) {
                    Runtime.getRuntime().exec("chmod +x ${file(".git/hooks/pre-commit").absolutePath}")
                }

                println("Git pre-commit hook installed successfully")
            }
        }
    }
}