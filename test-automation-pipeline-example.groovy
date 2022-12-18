def GitClone(String gitUrl, String auth = "",String localRepo = "", String branch = 'master', int timeout = 20, int depth = 1) {
    echo "===Start to pull $gitUrl branch: $branch to $localRepo==="
    checkout([$class: 'GitSCM', branches: [[name: "refs/heads/$branch"]], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: localRepo], [$class: 'CloneOption', depth: depth, honorRefspec: true, noTags: true, reference: '', shallow: true, timeout: timeout]], userRemoteConfigs: [[credentialsId: auth, refspec: "+refs/heads/$branch:refs/remotes/origin/$branch", url: gitUrl]]])
    boolean isSuccess = fileExists("$localRepo\\.git\\config")
    if (isSuccess) {
        echo "===End to pull ${gitUrl}==="
    } else {
        echo "===Fail in pulling ${gitUrl}==="
    }
    return isSuccess
}

pipeline {

    parameters {
        string(name: 'Agent', defaultValue: 'jenkins-node', description: 'Jenkins Test Agent Label')
        booleanParam(name: 'Reinstall', defaultValue: true, description: 'Reinstall Testable Windows Application If Checked')
        string(name: 'Version', defaultValue: '***', description: 'Testable Windows Application Version')
        string(name: 'DevBranch', defaultValue: "develop", description: 'Testable Windows Application Development Branch')
        string(name: 'TestBranch', defaultValue: "master", description: 'Test Automation Framework/Solution Branch For Testable Windows Application')
        string(name: 'Parameters', defaultValue:"parameter='value'", description: 'Current (Overridable) Test Run Settings Parameters')
        string(name: 'Tests', defaultValue: 'Smoke', description: 'Test Category (aka Test Scenarios Tag) - For Full Run (All Scenarios): Use "All" Value')
        booleanParam(name: 'CleanBefore', defaultValue: true, description: 'Delete Files In Jenkins Workspace Before Run If Checked')
        booleanParam(name: 'CleanAfter', defaultValue: false, description: 'Delete Files In Jenkins Workspace After Run If Checked')
        booleanParam(name: 'Report', defaultValue: true, description: 'Sends email results to specified below email addresses')
		string(name: 'Email', defaultValue: 'andrei.shendrykau@gmail.com', description: 'Test Run Report Email Recipient(s) - as To')
	}

    environment {
        TestAutomationGit='https://bitbucket.com/example.git'
        CredentialGuid = 'example-1111-0000-1111-example'
        
        TestProjectPath="${WORKSPACE}\\Tests"
        Dll="\"$TestProjectPath\\bin\\x86\\Debug\\Tests.dll\""
        TestRunSettings="$TestProjectPath\\tests.runsettings"
        TestRunParameters="@{ ${params.Parameters} }"

        WindowsAppVersion = "${params.Version}"
        WindowsAppArtifactName = "signed-App_${WindowsAppVersion}_AnyCPU.msix"
        WindowsAppArtifactPath = "release-generic/Example/${params.DevBranch}/${params.Version}/${WindowsAppArtifactName}"
        WindowsAppPackageId = "example-0000-1111-0000-example"
        WindoweAppPackageName = "${WindowsAppPackageId}_publisher!App"
    }

    agent { node { label params.Agent } }

    options {
        retry(1)
        timeout(unit: 'HOURS', time: 6)
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '1'))
        disableConcurrentBuilds()
    }

    stages {

        stage('Cleanup Before Run') {
            when {
                expression { params.CleanBefore }
            }
            steps {
                script {  }
                deleteDir()
                dir("${env.WORKSPACE}@tmp") { deleteDir() }
            }
        }

        stage('Test Automation Source Code Download') {
            steps {
                script {
                    if (!GitClone(env.TestAutomationGit, env.CredentialGuid, env.TestAutomationFrameworkFolder, params.TestBranch))  {
                        error 'Test Automation repo was pulled unsuccesfully'
                    }
                }
            }
        }

        stage('Download Testable Windows Package') {
            when {
                expression { params.Reinstall }
            }
            steps {
                timeout(unit: 'MINUTES', time: 30) {
                    script {
                        rtDownload (
                            serverId: "example-artifactory",
                            spec: """{
                                "files": [
                                    {
                                        "pattern": "${env.WindowsAppArtifactPath}",
                                        "flat": "true",
                                        "recursive":"true"
                                    }
                                        ]
                            }"""
                        )
                    }
                }
            }
        }
        
        stage('Reinstall Testable Windows App') {
            when {
                expression { params.Reinstall }
            }
            steps {
                script {
                    powershell(script: """
                        Get-Appxpackage *${env.WindowsAppPackageId}* | Remove-appxpackage
                        echo "Testable Windows App deleted"
                        """)
                }
                sleep(5)
                script {
                    powershell(script: """
                        Add-AppxPackage -Path '${WORKSPACE}\\${env.WindowsAppArtifactName}'
                        echo "${WORKSPACE}\\${env.WindowsAppArtifactName} installed"
                        """)
                }
            }
        }
        
        stage('Build Test Automation Solution') {
            steps {
                dir(env.TestAutomationFrameworkFolder){
                    script{
        		        echo "Nuget Packges Restore - 'nuget restore Example.sln'"
        			    bat "nuget restore Example.sln"
        			    echo "Build Test Automation Solution - 'msbuild Example.sln'"
        			    bat "msbuild Example.sln"
        		    }
                }
            }
        }
        
        stage('Update Test Run Settings') {
            steps {
                script {
                powershell(script: '''
                $xmlFileName = "$env:TestRunSettings"
                $parameters = "$env:TestRunParameters" -replace '@{' -replace '}' -replace ';',"`n" -replace "'" | ConvertFrom-StringData
                [xml]$xml = Get-Content $xmlFileName
                $nodelist = $xml.SelectNodes("//RunSettings/TestRunParameters/Parameter")
        		$parameters.GetEnumerator() | ForEach-Object {
        	    $parameter=$_.Key
        	    $node = $xml.RunSettings.TestRunParameters.Parameter | Where-Object { $_.name -eq $parameter }
       		    $node.value = $_.value }
                $xml.Save($xmlFileName)
                echo $parameters
                ''')
                }
            }
        }

        stage('Tests Run') {
            steps {
                dir(env.TestAutomationFrameworkFolder){
                    script{
						powershell(script: "Start-Process -Verb RunAs cmd.exe -Args '/c', 'taskkill /IM \"WinAppDriver.exe\" /F'")
                        powershell(script: '''
                            Get-Process chrome | ForEach-Object { $_.CloseMainWindow() | Out-Null}
                            echo "Chrome closed before test run"
                            ''')
                        powershell(script: '''
                            Get-Process msedge | ForEach-Object { $_.CloseMainWindow() | Out-Null}
                            echo "Edge closed before test run"
                            ''')
        			    if ("""${params.Tests}""".equalsIgnoreCase("All"))  {
        			        bat "echo 'All Tests Run - vstest.console.exe ${env.Dll} /Settings:\"${env.TestRunSettings}\"'"
        				    bat "vstest.console.exe ${env.Dll} /Settings:\"${env.TestRunSettings}\""
                        }
        			    else {
        			        bat "echo '\"${params.Tests}\" Tests Run - vstest.console.exe ${env.Dll} /Settings:\"${env.TestRunSettings}\" /TestCaseFilter:\"Category=${params.Tests}\"'"
        				    bat "vstest.console.exe ${env.Dll} /Settings:\"${env.TestRunSettings}\" /TestCaseFilter:\"Category=${params.Tests}\""
        			    }
        		    }
                }        		
            }
        }
    }
    
    post {
        always {
            script {
                powershell(script: "Start-Process -Verb RunAs cmd.exe -Args '/c', 'taskkill /IM \"WinAppDriver.exe\" /F'")
                powershell(script: '''
                    Get-Process chrome | ForEach-Object { $_.CloseMainWindow() | Out-Null}
                    echo "Chrome closed after test run"
                    ''')
                powershell(script: '''
                    Get-Process msedge | ForEach-Object { $_.CloseMainWindow() | Out-Null}
                    echo "Edge closed after test run"
                    ''')               
                if(params.Report){
                    println "Report through email To: ${params.Email}"
                    emailext(
						body: '${FILE, path="example.html" }',
						mimeType: 'text/html',
						recipientProviders: [[$class: 'RequesterRecipientProvider']],
						subject: "Example",
						to: '${params.Email}')
                }               
                if(params.CleanAfter){
                    println "Cleanup after Run"deleteDir()
                    dir("${env.WORKSPACE}@tmp") { deleteDir() }
                    script {
                        powershell(script: """
                            Get-Appxpackage *${env.WindowsAppPackageId}* | Remove-appxpackage
                            echo "Testable Windows App ${WindowsAppVersion} deleted"
                            """)
                    }
                }                
            }
        }
    }
}