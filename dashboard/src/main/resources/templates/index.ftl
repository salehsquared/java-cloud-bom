<!DOCTYPE html>
<html lang="en-US">
  <#include "macros.ftl">
  <head>
    <meta charset="utf-8" />
    <title>Google Cloud Platform Java Open Source Dependency Dashboard</title>
    <link rel="stylesheet" href="dashboard.css" />
    <script src="dashboard.js"></script>
  </head>
  <body>
    <h1>${coordinates} Dependency Status</h1>
    <hr />
    <#assign totalArtifacts = table?size>

    <div class="dropdown">
      <button class="dropdown-button">Other Versions</button>
      <div class="dropdown-content">
        <#list bomVersions as version>
          <a href="../${version?contains('-SNAPSHOT')?then('snapshot', version)}/index.html">${version}</a>
        </#list>
      </div>
    </div>

    <section class="statistics">
      <div class="container">
        <div class="statistic-item statistic-item-green">
          <h2>${table?size}</h2>
          <span class="desc">Total Artifacts Checked</span>
        </div>

        <div class="statistic-item statistic-item-orange">
          <#assign localUpperBoundsErrorCount = dashboardMain.countFailures(table, "Upper Bounds")>
          <h2>${dashboardMain.countFailures(table, "Upper Bounds")}</h2>
          <span class="desc">${(localUpperBoundsErrorCount == 1)?then("Has", "Have")} Upper Bounds Errors</span>
        </div>

        <div class="statistic-item statistic-item-blue">
          <#assign convergenceErrorCount = dashboardMain.countFailures(table, "Dependency Convergence")>
          <h2>${dashboardMain.countFailures(table, "Dependency Convergence")}</h2>
          <span class="desc">${(convergenceErrorCount == 1)?then("Fails", "Fail")} to Converge</span>
        </div>
      </div>
    </section>

    <h2>Library Versions</h2>
    <input type="text" id="filterBar" onkeyup="filterFunction()" placeholder="Search..">
    <table id="library versions">
      <tr class="header">
        <th>java-cloud-bom version</th>
        <th>artifact</th>
        <th>version in google-cloud-bom</th>
        <th>latest released version</th>
        <th>latest released date</th>
        <th>version of google-cloud-shared-dependencies</th>
      </tr>
      <#list artifacts as artifact>
        <#list versions as version>
          <tr>
              <#assign key = artifact + ":" + version>
              <th>${version}</th>
              <th>${artifact}</th>
              <th><a target="_blank" href=${sharedDepsPosition[key]}>${currentVersion[key]}</a></th>
              <th><a target="_blank" href=${newestPomURL[key]}>${newestVersion[key]}</a></th>
              <th><a target="_blank" href=${metadataURL[key]}>${updatedTime[key]}</a></th>
              <th>${sharedDepsVersion[key]}</th>
          </tr>
        </#list>
      </#list>
    </table>

    <hr />

    <p id='updated'>Last generated at ${lastUpdated}</p>

    <script>
      function colsContainAllInput(cols, input) {
        for(let i = 0; i < input.length; i++) {
            if(input[i].length == 0) {
                continue;
            }
            let found = false;
            for(let j = 0; j < cols.length; j++) {
                let name = cols[j].textContent || cols[j].innerText;
                if(name.toLowerCase().indexOf(input[i]) > -1) {
                    found = true;
                }
            }
            if(!found) {
                return false;
            }
        }
        return true;
      }

      function filterFunction() {
        const input = document.getElementById("filterBar").value.toLowerCase();
        if(input === "") {
            for (let i = 1; i < rows.length; i++) {
                rows[i].style.display = "";
            }
            return;
        }
        const splitInput = input.split(" ");
        const table = document.getElementById("library versions");
        const rows = table.getElementsByTagName("tr");
        for (let i = 1; i < rows.length; i++) {
          const cols = rows[i].getElementsByTagName("th");
          let isDisplay = colsContainAllInput(cols, splitInput);
          rows[i].style.display = isDisplay ? "" : "none";
        }
      }
    </script>
  </body>
</html>