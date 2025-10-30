properties([buildDiscarder(logRotator(numToKeepStr: '20'))])
node('maven-17') {
    checkout scm
    timeout(time: 1, unit: 'HOURS') {
        ansiColor('xterm') {
            withEnv(['MAVEN_OPTS=-Djansi.force=true']) {
                sh 'mvn -B -Dstyle.color=always -ntp clean verify'
            }
        }
    }
}
