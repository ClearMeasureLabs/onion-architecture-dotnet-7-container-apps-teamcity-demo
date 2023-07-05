import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.dockerCommand
import jetbrains.buildServer.configs.kotlin.buildSteps.powerShell
import jetbrains.buildServer.configs.kotlin.projectFeatures.dockerRegistry
import jetbrains.buildServer.configs.kotlin.triggers.vcs

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2023.05"

project {

    buildType(IntegrationBuild)
    buildType(Build)

    params {
        param("env.BUILD_BUILDNUMBER", "%build.number%")
        param("env.BuildConfiguration", "Release")
        param("env.Version", "%build.number%")
    }

    features {
        dockerRegistry {
            id = "PROJECT_EXT_3"
            name = "Onion-Arch ACR"
            url = "onionarchitecturedotnet7containers.azurecr.io"
            userName = "767d5e60-4d25-4794-9a4d-f714fab829e0"
            password = "credentialsJSON:b66a8739-aa0b-4987-a245-07c6907bdd01"
        }
    }
    buildTypesOrder = arrayListOf(IntegrationBuild, Build)
}

object Build : BuildType({
    name = "DockerBuildAndPush"

    buildNumberPattern = "${IntegrationBuild.depParamRefs.buildNumber}"

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        powerShell {
            name = "Install UI nupkg"
            scriptMode = script {
                content = """
                    dir
                    Get-ChildItem -Path build | Select-Object -Property Name
                    ${'$'}nupkgPath = "build/ChurchBulletin.UI.%build.number%.nupkg"
                    ${'$'}destinationPath = "built"
                    
                    Add-Type -AssemblyName System.IO.Compression.FileSystem
                    
                    [System.IO.Compression.ZipFile]::ExtractToDirectory(${'$'}nupkgPath, ${'$'}destinationPath)
                """.trimIndent()
            }
        }
        dockerCommand {
            name = "Docker Build"
            commandType = build {
                source = file {
                    path = "Dockerfile"
                }
                contextDir = "."
                namesAndTags = "churchbulletin.ui:%build.number%"
            }
        }
        dockerCommand {
            name = "Docker Push"
            commandType = push {
                namesAndTags = "churchbulletin.ui:%build.number%"
            }
        }
    }

    triggers {
        vcs {
        }
    }

    features {
        perfmon {
        }
        dockerSupport {
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_3"
            }
        }
    }

    dependencies {
        dependency(IntegrationBuild) {
            snapshot {
            }

            artifacts {
                cleanDestination = true
                artifactRules = "+:**=>build"
            }
        }
    }
})

object IntegrationBuild : BuildType({
    name = "Integration Build"

    allowExternalStatus = true
    artifactRules = """build\*.nupkg"""
    buildNumberPattern = "3.0.%build.counter%"

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        powerShell {
            name = "Enable LocalDB"
            formatStderrAsError = true
            scriptMode = script {
                content = """
                    # Download the SqlLocalDB.msi installer from the Microsoft website
                    ${'$'}installerPath = "${'$'}env:TEMP\SqlLocalDB.msi"
                    Invoke-WebRequest "https://download.microsoft.com/download/7/c/1/7c14e92e-bdcb-4f89-b7cf-93543e7112d1/SqlLocalDB.msi" -OutFile ${'$'}installerPath
                    
                    # Install SqlLocalDB
                    Start-Process -FilePath msiexec -ArgumentList "/i `"${'$'}installerPath`" /qn IACCEPTSQLLOCALDBLICENSETERMS=YES" -Wait
                    
                    # Remove the installer file
                    Remove-Item ${'$'}installerPath
                    
                    # Reload env vars
                    ${'$'}env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
                    
                    Write-Host "Starting LocalDB"
                    sqllocaldb start mssqllocaldb
                """.trimIndent()
            }
        }
        powerShell {
            name = "Build.ps1"
            scriptMode = script {
                content = """
                    dotnet tool install Octopus.DotNet.Cli --global
                    
                    # Reload env vars
                    ${'$'}env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
                    
                    # Run build script
                    . .\build.ps1 ; CIBuild
                """.trimIndent()
            }
        }
    }

    triggers {
        vcs {
        }
    }

    features {
        perfmon {
        }
    }

    requirements {
        matches("teamcity.agent.jvm.os.family", "Windows")
    }
})
