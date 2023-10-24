podTemplate(yaml: '''
    apiVersion: v1
    kind: Pod
    metadata:
      labels:
        name: jenkins-<GITLAB_GROUP>-deploy
    spec:
      containers:
      - name: jnlp
        image: <PRIVATE_REGISTRY_ADDRESS>/jenkins/inbound-agent:4.10-3-jdk11
      - name: kubectl
        image: <PRIVATE_REGISTRY_ADDRESS>/kubectl-oc-new:1.20.1
        command:
         - sleep
        args:
         - 99d
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
    ''', cloud: 'kubernetes')
        {
            node(POD_LABEL) {
                ansiColor('css') {
                    wrap([$class: 'BuildUser']) {
                        currentBuild.displayName = "#${BUILD_NUMBER} - ${NAMESPACE} --> ${COMMIT_MSG} ${SERVICE} to ${VERSION}"
                        slackSend channel: "#<SOME_SLACK_CHANNEL>", color: "#02fe21", message: "${JOB_NAME} started by ${env.BUILD_USER} : #${BUILD_NUMBER} - ${NAMESPACE} --> ${COMMIT_MSG} ${SERVICE} to ${VERSION}"
                            stage('SCM') {
                                git url: 'git@<GITLAB_ADDRESS>:<GITLAB_GROUP>/kubernetes.git', branch: 'master', credentialsId: 'GitDev'
                                container('kubectl') {
                                    stage('Tag') {
                                        sh '''
                                            cp $NAMESPACE/$TYPE/$SERVICE.yaml /$SERVICE.yaml
                                            OLDTAG=`cat $NAMESPACE/$TYPE/$SERVICE.yaml | grep image: | awk {'print $2'} | cut -d: -f3`
                                            IMAGENAME=`cat $NAMESPACE/$TYPE/$SERVICE.yaml | grep image: | awk {'print $2'} | cut -d: -f1,2`
                                            sed "s,$IMAGENAME:$OLDTAG,$IMAGENAME:$VERSION,g" -i $NAMESPACE/$TYPE/$SERVICE.yaml
                                            cp $NAMESPACE/$TYPE/$SERVICE.yaml /$SERVICE-new.yaml
                                        '''

                                    }
                                    stage('Deploy') {
                                        withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'KUBECONFIG_CONTENT')])
                                                {
                                                    if (params.MIGRATION == 'yes') {
                                                        sh '''
                                                            sed "s/`cat $NAMESPACE/$TYPE/$SERVICE.yaml | grep -A 10 "livenessProbe:" | grep "initialDelaySeconds:"`/          initialDelaySeconds: $MIGRATION_TIME/g" -i $NAMESPACE/$TYPE/$SERVICE.yaml
                                                        '''
                                                    }
                                                    sh '''
                                                        mkdir -p ~/.kube
                                                        cat $KUBECONFIG_CONTENT > ~/.kube/config
                                                        kubectl apply -n $NAMESPACE -f $NAMESPACE/$TYPE/$SERVICE.yaml
                                                        cp -a /$SERVICE-new.yaml $NAMESPACE/$TYPE/$SERVICE.yaml
                                                    '''
                                                }
                                    }
                                    stage('IsItReady?') {
                                        withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'KUBECONFIG_CONTENT')])
                                                {
                                                    sleep 2
                                                    sh '''
                                                        mkdir -p ~/.kube
                                                        cat $KUBECONFIG_CONTENT > ~/.kube/config
                                                        kubectl rollout status $TYPE -n $NAMESPACE $SERVICE
                                                        kubectl get pods -n $NAMESPACE -l name=$SERVICE
                                                    '''
                                                }
                                    }
                                    stage('Migration?') {

                                        if (params.MIGRATION == 'yes') {
                                            withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'KUBECONFIG_CONTENT')])
                                                    {
                                                        sh '''
                                                            mkdir -p ~/.kube
                                                            cat $KUBECONFIG_CONTENT > ~/.kube/config
                                                            kubectl apply -n $NAMESPACE -f /$SERVICE-new.yaml
                                                        '''
                                                        sleep 5
                                                        sh '''
                                                            kubectl rollout status $TYPE -n $NAMESPACE $SERVICE
                                                            kubectl get pods -n $NAMESPACE -l name=$SERVICE
                                                        '''
                                                    }
                                        } else {
                                            echo 'Skipped'
                                        }
                                    }
                                    stage('Push') {
                                        withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'KUBECONFIG_CONTENT')])
                                                {
                                                    try {
                                                        sshagent(credentials: ['GitDev']) {
                                                            sh '''
                                                                git config --global --add safe.directory "*"
                                                                git config user.name "<SOME_USERNAME>"
                                                                git config user.email "<SOME_EMAIL>"
                                                                git add $NAMESPACE/$TYPE/$SERVICE.yaml
                                                                git commit -m "chore($SERVICE): $NAMESPACE --> $COMMIT_MSG to $VERSION"
                                                                GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no" git push git@<GITLAB_ADDRESS>:<GITLAB_GROUP>/kubernetes.git -u master
                                                            '''
                                                        }
                                                    }
                                                    catch (ignored) {
                                                        sh '''
                                                            StartColorGreen="\033[42m\033[97m"
                                                            StartColorRed="\033[41m\033[97m"
                                                            EndColor="\033[0m"
                                                            mkdir -p ~/.kube
                                                            cat $KUBECONFIG_CONTENT > ~/.kube/config
                                                            echo "$StartColorRed there was an Erro when you want to push new tag $EndColor"
                                                            kubectl apply -n $NAMESPACE -f /$SERVICE.yaml
                                                            sleep 5
                                                            kubectl rollout status $TYPE -n $NAMESPACE $SERVICE
                                                            kubectl get pods -n $NAMESPACE -l name=$SERVICE
                                                            echo "$StartColorGreen Your deployment go to old tag successfully and pipeline exit now $EndColor"
                                                            exit 200
                                                        '''
                                                    }
                                                }
                                    }
                                }
                            }
                        slackSend channel: "#jenkins-<GITLAB_GROUP>", color: "#f0f213", message: "${JOB_NAME} runned by ${env.BUILD_USER} finished successfully : #${BUILD_NUMBER} - ${NAMESPACE} --> ${COMMIT_MSG} ${SERVICE} to ${VERSION}"
                    }
                }
            }
        }
