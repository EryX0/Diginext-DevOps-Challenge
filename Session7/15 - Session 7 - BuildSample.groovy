podTemplate(yaml: '''
    apiVersion: v1
    kind: Pod
    metadata:
      labels:
        name: Jenkins-build-<GITLAB_GROUP>-backend
    spec:
      containers:
      - name: jnlp
        image: <PRIVATE_REGISTRY_ADDRESS>/jenkins/inbound-agent:4.10-3-jdk11
      - name: docker
        image: <PRIVATE_REGISTRY_ADDRESS>/docker:20.10.7-git
        volumeMounts:
         - mountPath: /var/run/docker.sock
           name: docker-sock
        command:
         - cat
        tty: true
      imagePullSecrets:
      - name: regsecret
      hostAliases:
      - ip: "<IP_ADDRESS_OF_K8S_API_SERVER_LB>"
        hostnames:
        - "<HOSTNAME_OF_K8S_API_SERVER_LB>"
      volumes:
      - name: docker-sock
        hostPath:
          path: /var/run/docker.sock
    ''', cloud: 'kubernetes') {
    node(POD_LABEL) {
        ansiColor('css') {
            wrap([$class: 'BuildUser']) {
                currentBuild.displayName = "#${BUILD_NUMBER} - ${env.BUILD_USER} --> Build from branch ${BRANCH}"
                slackSend channel: "#<SOME_SLACK_CHANNEL>", color: "#02fe21", message: "${JOB_NAME} started by ${env.BUILD_USER} : #${BUILD_NUMBER} --> Build from ${BRANCH} branch with ${TAG} TAG"
                stage('SCM') {
                    git url: 'git@<GITLAB_ADDRESS>:<GITLAB_GROUP>/${SERVICE}.git', branch: '$BRANCH', credentialsId: 'GitDev'
                    container('docker') {
                        stage('Build') {
                            withCredentials([
                                    string(credentialsId: 'NexusPass', variable: 'PW')
                            ]) {
                                sh '''#!/bin/bash
                                        StartColorGreen="\033[42m\033[97m"
                                        StartColorRed="\033[41m\033[97m"
                                        EndColor="\033[0m"                                      
                                        ## check tag is define from user or define automatically from gitlab webhook 
                                        if [[ -z "${TAG}" ]]
                                            then
                                                TAG=$(git tag --sort version:refname | tail -1)
                                                tempp=$(git branch -a --contains $TAG | grep master | cut -d/ -f3)
                                                if [[ "${tempp}" == "master" ]]
                                                    then
                                                        echo "$StartColorGreen your tag created from master branch $EndColor"
                                                    else
                                                        echo "$StartColorRed your tag does not created from master branch $EndColor"
                                                        exit 100
                                                fi
                                            else
                                                echo "$StartColorGreen TAG is define to ${TAG} $EndColor"
                                            fi
                                        ## check tag pattern
                                        if [[ $TAG =~ (^([vV]?[0-9]{1,2}+\\.[0-9]{1,2}+\\.[0-9]{1,2}$)|(^BOX-[0-9]{1,5})$) ]]
                                            then
                                                echo "$StartColorGreen Your tag is correct $EndColor"
                                            else
                                                echo "$StartColorRed Your tag is not correct. supported pattern is v[int].[int].[int] or [int].[int].[int] or BOX-[int]{1,5} $EndColor"
                                                exit 100
                                        fi                                             
                                        docker login -u <SOME_USERNAME> -p${PW} <PRIVATE_REGISTRY_ADDRESS>
                                        docker build --build-arg DEV_ENV=$DEV_ENV -t <PRIVATE_REGISTRY_ADDRESS>/<GITLAB_GROUP>/${SERVICE}/<GITLAB_GROUP>-${SERVICE}:$TAG .
                                    '''
                            }
                            stage('Push') {
                                sh '''
                                        if [[ -z "${TAG}" ]]; then TAG=$(git tag --sort version:refname | tail -1)  ; else echo "TAG is define to ${TAG}"; fi
                                        docker push <PRIVATE_REGISTRY_ADDRESS>/<GITLAB_GROUP>/<GITLAB_GROUP>-${SERVICE}/${SERVICE}:$TAG                                      
                                    '''
                            }
                            slackSend channel: "#jenkins-<GITLAB_GROUP>", color: "#f0f213", message: "${JOB_NAME} runned by ${env.BUILD_USER} finished successfully  : #${BUILD_NUMBER} --> Build from ${BRANCH} branch with ${TAG} TAG"
                        }
                    }
                }
            }
        }
    }
}
