Example of a Jenkins pipeline for a Test Automation flow, with following stages: 
- Cleanup workspace folder before test run
- Test Automation Solution Download
- Download the latest version of Testable Windows Application Package from artifactory source
- Reinstall Testable Windows Application
- Build Test Automation Solution
- Update Test Run Settings
- Tests Run
- Send reporting through email
- Cleanup workspace folder after test run
