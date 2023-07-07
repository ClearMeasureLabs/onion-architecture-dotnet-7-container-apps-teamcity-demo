import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.DotnetVsTestStep
import jetbrains.buildServer.configs.kotlin.buildSteps.dockerCommand
import jetbrains.buildServer.configs.kotlin.buildSteps.dotnetVsTest
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

    buildType(Tdd)
    buildType(IntegrationBuild)
    buildType(Build)
    buildType(DeleteTdd)

    params {
        param("env.containerAppURL", "")
        param("OctoSpace", "Spaces-195")
        param("env.BuildConfiguration", "Release")
        param("TDD-Resource-Group", "onion-architecture-dotnet-7-containers-TDD")
        param("TDD-App-Name", "tdd-ui")
        param("OctoProject", "teamcity-dotnet-7-container-apps")
        param("OctoSpaceName", "Onion DevOps")
        param("env.BUILD_BUILDNUMBER", "%build.number%")
        param("AzAppId", "767d5e60-4d25-4794-9a4d-f714fab829e0")
        param("env.Version", "%build.number%")
        password("AzPassword", "credentialsJSON:b66a8739-aa0b-4987-a245-07c6907bdd01")
        param("OctoURL", "https://clearmeasure.octopus.app/")
        password("OctoApiKey", "credentialsJSON:959b363e-7a9f-4706-86fa-532f285020e7", label = "OctoApiKey")
        password("AzTenant", "credentialsJSON:d16337c7-5751-4ecd-9110-f82755b0ebca")
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
                namesAndTags = "onionarchitecturedotnet7containers.azurecr.io/churchbulletin.ui:%build.number%"
            }
        }
        dockerCommand {
            name = "Docker Push"
            commandType = push {
                namesAndTags = "onionarchitecturedotnet7containers.azurecr.io/churchbulletin.ui:%build.number%"
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

object DeleteTdd : BuildType({
    name = "Delete TDD"

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        powerShell {
            name = "Delete TDD Resources"
            scriptMode = script {
                content = """
                    Invoke-WebRequest -Uri https://aka.ms/installazurecliwindows -OutFile .\AzureCLI.msi
                    Start-Process msiexec.exe -Wait -ArgumentList '/I AzureCLI.msi /quiet'
                    Remove-Item .\AzureCLI.msi
                    ${'$'}env:PATH += ";C:\Program Files (x86)\Microsoft SDKs\Azure\CLI2\wbin"
                    
                    
                    az config set extension.use_dynamic_install=yes_without_prompt
                    # Log in to Azure
                    az login --service-principal --username %AzAppId% --password %AzPassword% --tenant %AzTenant%
                    
                    az group delete -n %TDD-Resource-Group%-%build.number% --yes
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

    dependencies {
        snapshot(Tdd) {
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
                    
                    foreach(${'$'}file in (Get-ChildItem ".\build\" -Recurse -Include *.nupkg)) {
                    	dotnet octo push --server=%OctoURL%/ --apiKey=%OctoApiKey% --space=%OctoSpace% --package ${'$'}file
                    }
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

object Tdd : BuildType({
    name = "TDD"

    buildNumberPattern = "${IntegrationBuild.depParamRefs.buildNumber}"

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        step {
            name = "Create and Deploy Release"
            type = "octopus.create.release"
            param("secure:octopus_apikey", "credentialsJSON:76162b23-1358-46ea-8823-ca95bfad6401")
            param("octopus_releasenumber", "%build.number%")
            param("octopus_additionalcommandlinearguments", "--variable=ResourceGroupName:%TDD-Resource-Group%-%build.number% --variable=container_app_name:%TDD-App-Name%")
            param("octopus_space_name", "%OctoSpaceName%")
            param("octopus_waitfordeployments", "true")
            param("octopus_version", "3.0+")
            param("octopus_host", "%OctoURL%")
            param("octopus_project_name", "%OctoProject%")
            param("octopus_deploymenttimeout", "00:30:00")
            param("octopus_deployto", "TDD")
            param("octopus_git_ref", "%teamcity.build.branch%")
        }
        powerShell {
            name = "Get Container App URL"
            scriptMode = script {
                content = """
                    Invoke-WebRequest -Uri https://aka.ms/installazurecliwindows -OutFile .\AzureCLI.msi
                    Start-Process msiexec.exe -Wait -ArgumentList '/I AzureCLI.msi /quiet'
                    Remove-Item .\AzureCLI.msi
                    ${'$'}env:PATH += ";C:\Program Files (x86)\Microsoft SDKs\Azure\CLI2\wbin"
                    
                    
                    az config set extension.use_dynamic_install=yes_without_prompt
                    # Log in to Azure
                    az login --service-principal --username %AzAppId% --password %AzPassword% --tenant %AzTenant%
                    ${'$'}containerAppURL = az containerapp show --resource-group %TDD-Resource-Group%-%build.number% --name %TDD-App-Name% --query properties.configuration.ingress.fqdn
                    ${'$'}containerAppURL = ${'$'}containerAppURL -replace '"', ''
                    Write-Host "url retrieved from AZ: ${'$'}containerAppURL"
                    [System.Environment]::SetEnvironmentVariable("containerAppURL", ${'$'}containerAppURL, "Machine")
                    Write-Host "ContainerAppURL after retrieval: ${'$'}env:containerAppURL"
                """.trimIndent()
            }
        }
        powerShell {
            name = "Download Acceptance Test Package"
            scriptMode = script {
                content = """
                    ${'$'}nupkgPath = "build/ChurchBulletin.AcceptanceTests.%build.number%.nupkg"
                    ${'$'}destinationPath = "."
                    
                    Add-Type -AssemblyName System.IO.Compression.FileSystem
                    
                    [System.IO.Compression.ZipFile]::ExtractToDirectory(${'$'}nupkgPath, ${'$'}destinationPath)
                    ${'$'}currentPath = (Get-Location).Path
                    # Set the download URL for the Chrome driver
                    ${'$'}chromeDriverUrl = "http://chromedriver.storage.googleapis.com/114.0.5735.90/chromedriver_linux64.zip"
                    ${'$'}chromeDriverPath = "./chromedriver.zip"
                    
                    # Download the Chrome driver
                    Invoke-WebRequest -Uri ${'$'}chromeDriverUrl -OutFile ${'$'}chromeDriverPath
                    
                    ${'$'}chromedriverdestinationPath = "C:\SeleniumWebDrivers\ChromeDriver"
                    
                    Expand-Archive -Path ${'$'}chromeDriverPath -DestinationPath ${'$'}chromedriverdestinationPath
                    
                    # Add the Chrome driver to the PATH environment variable
                    ${'$'}env:PATH += ";${'$'}chromedriverdestinationPath"
                """.trimIndent()
            }
        }
        dotnetVsTest {
            name = "Run Acceptance Tests"
            assemblies = "*AcceptanceTests.dll"
            version = DotnetVsTestStep.VSTestVersion.CrossPlatform
            platform = DotnetVsTestStep.Platform.Auto
            param("dotNetCoverage.dotCover.home.path", "%teamcity.tool.JetBrains.dotCover.CommandLineTools.DEFAULT%")
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

    dependencies {
        snapshot(Build) {
        }
        artifacts(IntegrationBuild) {
            artifactRules = "+:**=>build"
        }
    }

    requirements {
        matches("teamcity.agent.jvm.os.family", "Windows")
    }
})
