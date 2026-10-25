pipeline {
    agent {
        label 'dockerhost'
    }

    triggers {
        githubPush()
    }

    options {
        timestamps()
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
    }

    parameters {
        choice(
            name: 'REGISTRY',
            choices: ['DOCKERHUB', 'ECR'],
            description: 'Choose Docker Hub or AWS ECR'
        )
    }

    environment {
        SONAR_SERVER = 'SonarQube'
        SONAR_PROJECT_KEY = 'sonarqube-demo-new'

        DOCKERHUB_CREDENTIALS = 'dockerhub-creds'
        DOCKERHUB_REPO = 'ninande/my-web-app-new'

        AWS_REGION = 'ap-south-1'
        AWS_ACCOUNT_ID = '699258777180'
        ECR_REPO = 'my-web-app'

        // Jenkins agent connects to this separate Ansible EC2.
        ANSIBLE_HOST = '10.0.11.194'
        ANSIBLE_USER = 'ansadmin'
        ANSIBLE_INVENTORY = '/opt/ansible/my-web-app/inventory.ini'
        ANSIBLE_PLAYBOOK  = '/opt/ansible/my-web-app/deploy.yml'
       // ANSIBLE_DIR = '/opt/ansible/my-web-app'
       // ANSIBLE_CREDENTIALS = 'ansible-control-ssh-key'
       // ANSIBLE_KNOWN_HOSTS_CREDENTIALS = 'ansible-known-hosts'

   // IMAGE_NAME       = 'my-web-app-new'
   // IMAGE_TAG        = 'latest'
    // REGISTRY         = '699258777180.dkr.ecr.ap-south-1.amazonaws.com'
   // AWS_REGION       = 'ap-south-1'
    //AWS_ACCOUNT_ID   = '699258777180'
        COMPOSE_DIR = '/opt/my-web-app-new'
        NOTIFY_EMAIL = 'shreevdeshmukh@gmail.com'
    }

    stages {
        stage('1. Checkout') {
            steps {
                checkout scm

                sh '''
                    set -eu
                    git log -1 --oneline
                    test -f pom.xml
                    test -f Dockerfile
                 //   test -f ansible/inventory.ini
                 //   test -f ansible/deploy.yml
                '''
            }
        }

        stage('2. Build and Test') {
            steps {
                sh 'mvn -B clean verify'
            }

            post {
                always {
                    junit(
                        testResults: 'target/surefire-reports/TEST-*.xml',
                        allowEmptyResults: true
                    )
                }
            }
        }

        stage('3. SonarQube Analysis and Quality Gate') {
            steps {
                withSonarQubeEnv("${SONAR_SERVER}") {
                    sh '''
                        set -eu

                        mvn -B \
                          org.sonarsource.scanner.maven:sonar-maven-plugin:5.2.0.4988:sonar \
                          -Dsonar.projectKey="${SONAR_PROJECT_KEY}" \
                          -Dsonar.projectName="SonarQube-Demo-New" \
                          -Dsonar.host.url="${SONAR_HOST_URL}"
                    '''
                }

                timeout(time: 10, unit: 'MINUTES') {
                    script {
                        def qualityGate = waitForQualityGate(
                            abortPipeline: true
                        )

                        if (qualityGate.status != 'OK') {
                            error "SonarQube Quality Gate failed: ${qualityGate.status}"
                        }

                        echo 'SonarQube Quality Gate passed.'
                    }
                }
            }
        }

        stage('4. Build Image and Trivy Security Scan') {
            steps {
                script {
                    if (params.REGISTRY == 'DOCKERHUB') {
                        env.IMAGE_NAME = env.DOCKERHUB_REPO
                    } else {
                        env.IMAGE_NAME =
                            "${env.AWS_ACCOUNT_ID}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${env.ECR_REPO}"
                    }

                    env.IMAGE_TAG = "${env.BUILD_NUMBER}"
                }

                sh '''
                    set -eu

                    mkdir -p reports

                    echo "Scanning source files..."

                    trivy fs \
                      --scanners vuln,secret,misconfig \
                      --format json \
                      --output reports/trivy-fs-report.json \
                      .

                    echo "Building ${IMAGE_NAME}:${IMAGE_TAG}..."

                    docker build \
                      -t "${IMAGE_NAME}:${IMAGE_TAG}" \
                      -t "${IMAGE_NAME}:latest" \
                      .

                    echo "Scanning container image..."

                    trivy image \
                      --severity CRITICAL \
                      --ignore-unfixed \
                      --exit-code 1 \
                      --format json \
                      --output reports/trivy-image-report.json \
                      "${IMAGE_NAME}:${IMAGE_TAG}"

                    echo 'Trivy image security gate passed.'
                '''
            }
        }

       
 stage('5.Push to Registry') {
            steps {
                script {
                    if (params.REGISTRY == 'DOCKERHUB') {
                        withCredentials([
                            usernamePassword(
                                credentialsId: env.DOCKERHUB_CREDENTIALS,
                                usernameVariable: 'DH_USER',
                                passwordVariable: 'DH_TOKEN'
                            )
                        ]) {
                            sh '''
                                set -eu
                                set +x

                                echo "$DH_TOKEN" | docker login \
                                  --username "$DH_USER" \
                                  --password-stdin

                                docker push "${IMAGE_NAME}:${IMAGE_TAG}"
                                docker push "${IMAGE_NAME}:latest"

                                docker logout || true
                            '''
                        }
                    } else {
                        sh '''
                            set -eu
                            set +x

                            aws ecr get-login-password \
                              --region "${AWS_REGION}" |
                              docker login \
                                --username AWS \
                                --password-stdin \
                                "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

                            docker push "${IMAGE_NAME}:${IMAGE_TAG}"
                            docker push "${IMAGE_NAME}:latest"

                            docker logout \
                              "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com" || true
                        '''
                    }
                }
            }
        }
    
 stage('6.Deploy with Docker Compose') {
            steps {
                script {
                    if (params.REGISTRY == 'DOCKERHUB') {
                        withCredentials([
                            usernamePassword(
                                credentialsId: env.DOCKERHUB_CREDENTIALS,
                                usernameVariable: 'DH_USER',
                                passwordVariable: 'DH_TOKEN'
                            )
                        ]) {
                            sh '''
                                set -eu
                                set +x

                                cd "${COMPOSE_DIR}"

                                echo "$DH_TOKEN" | docker login \
                                  --username "$DH_USER" \
                                  --password-stdin

                                export IMAGE_NAME="${IMAGE_NAME}"
                                export IMAGE_TAG="${IMAGE_TAG}"

                                docker compose config -q
                                docker compose pull
                                docker compose up -d --remove-orphans
                                docker compose ps

                                docker logout || true
                            '''
                        }
                    } else {
                        sh '''
                            set -eu
                            set +x

                            cd "${COMPOSE_DIR}"

                            aws ecr get-login-password \
                              --region "${AWS_REGION}" |
                              docker login \
                                --username AWS \
                                --password-stdin \
                                "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

                            export IMAGE_NAME="${IMAGE_NAME}"
                            export IMAGE_TAG="${IMAGE_TAG}"

                            docker compose config -q
                            docker compose pull
                            docker compose up -d --remove-orphans
                            docker compose ps

                            docker logout \
                              "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com" || true
                        '''
                    }
                }
            }
        }
stage('7. Deploy to Dev and Staging via Ansible EC2') {
    steps {
        withCredentials([
            sshUserPrivateKey(
                credentialsId: 'ansible-control-ssh-key',
                keyFileVariable: 'ANSIBLE_KEY'
            ),
            file(
                credentialsId: 'ansible-known-hosts',
                variable: 'KNOWN_HOSTS_FILE'
            )
        ]) {
            sh '''
                set -eu

                SSH_OPTS="-o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10"

                echo "Checking SSH access to Ansible EC2..."

                ssh $SSH_OPTS \
                    -i "$ANSIBLE_KEY" \
                    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
                    "${ANSIBLE_USER}@${ANSIBLE_HOST}" \
                    'command -v ansible-playbook &&
                     test -r /opt/ansible/my-web-app/inventory.ini &&
                     test -r /opt/ansible/my-web-app/deploy.yml'

                echo "Deploying to Dev..."

                ssh $SSH_OPTS \
                    -i "$ANSIBLE_KEY" \
                    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
                    "${ANSIBLE_USER}@${ANSIBLE_HOST}" \
                    "ansible-playbook \
                     -i '${ANSIBLE_INVENTORY}' \
                     '${ANSIBLE_PLAYBOOK}' \
                     --limit dev \
                     -e 'image_name=${IMAGE_NAME}' \
                     -e 'image_tag=${IMAGE_TAG}' \
                     -e 'registry=${REGISTRY}' \
                     -e 'aws_region=${AWS_REGION}' \
                     -e 'aws_account_id=${AWS_ACCOUNT_ID}'"

                echo "Deploying to Staging..."

                ssh $SSH_OPTS \
                    -i "$ANSIBLE_KEY" \
                    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
                    "${ANSIBLE_USER}@${ANSIBLE_HOST}" \
                    "ansible-playbook \
                     -i '${ANSIBLE_INVENTORY}' \
                     '${ANSIBLE_PLAYBOOK}' \
                     --limit staging \
                     -e 'image_name=${IMAGE_NAME}' \
                     -e 'image_tag=${IMAGE_TAG}' \
                     -e 'registry=${REGISTRY}' \
                     -e 'aws_region=${AWS_REGION}' \
                     -e 'aws_account_id=${AWS_ACCOUNT_ID}'"
            '''
        }
    }
}
        stage('7. Manual Approval - Production') {
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input(
                        message: "Approve production deployment of ${IMAGE_NAME}:${IMAGE_TAG}?",
                        ok: 'Approve Production',
                        submitter: 'admin'
                    )
                }
            }
        }

        


stage('8. Deploy to Production via Ansible EC2') {
    steps {
        withCredentials([
            sshUserPrivateKey(
                credentialsId: 'ansible-control-ssh-key',
                keyFileVariable: 'ANSIBLE_KEY'
            ),
            file(
                credentialsId: 'ansible-known-hosts',
                variable: 'KNOWN_HOSTS_FILE'
            )
        ]) {
            sh '''
                set -eu

                SSH_OPTS="-o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10"

                echo "========================================"
                echo "PRODUCTION DEPLOYMENT"
                echo "========================================"

                echo "Step 1: Checking required variables..."

                test -n "$ANSIBLE_HOST"
                test -n "$ANSIBLE_USER"
                test -n "$ANSIBLE_INVENTORY"
                test -n "$ANSIBLE_PLAYBOOK"
                test -n "$IMAGE_NAME"
                test -n "$IMAGE_TAG"
                test -n "$REGISTRY"

                echo "Ansible host: $ANSIBLE_HOST"
                echo "Image name: $IMAGE_NAME"
                echo "Image tag: $IMAGE_TAG"
                echo "Registry: $REGISTRY"

                echo "Step 2: Checking SSH access to Ansible EC2..."

                ssh $SSH_OPTS \
                    -i "$ANSIBLE_KEY" \
                    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
                    "${ANSIBLE_USER}@${ANSIBLE_HOST}" \
                    'whoami &&
                     command -v ansible-playbook &&
                     test -r /opt/ansible/my-web-app/inventory.ini &&
                     test -r /opt/ansible/my-web-app/deploy.yml'

                echo "Step 3: Checking production inventory group..."

                ssh $SSH_OPTS \
                    -i "$ANSIBLE_KEY" \
                    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
                    "${ANSIBLE_USER}@${ANSIBLE_HOST}" \
                    "ansible-inventory \
                     -i '${ANSIBLE_INVENTORY}' \
                     --graph"

                echo "Step 4: Checking production playbook syntax..."

                ssh $SSH_OPTS \
                    -i "$ANSIBLE_KEY" \
                    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
                    "${ANSIBLE_USER}@${ANSIBLE_HOST}" \
                    "ansible-playbook \
                     -i '${ANSIBLE_INVENTORY}' \
                     '${ANSIBLE_PLAYBOOK}' \
                     --limit prod \
                     --syntax-check"

                echo "Step 5: Deploying to Production..."

                ssh $SSH_OPTS \
                    -i "$ANSIBLE_KEY" \
                    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
                    "${ANSIBLE_USER}@${ANSIBLE_HOST}" \
                    "ansible-playbook \
                     -i '${ANSIBLE_INVENTORY}' \
                     '${ANSIBLE_PLAYBOOK}' \
                     --limit prod \
                     -e 'image_name=${IMAGE_NAME}' \
                     -e 'image_tag=${IMAGE_TAG}' \
                     -e 'registry=${REGISTRY}' \
                     -e 'aws_region=${AWS_REGION}' \
                     -e 'aws_account_id=${AWS_ACCOUNT_ID}'"

                echo "========================================"
                echo "PRODUCTION DEPLOYMENT COMPLETED"
                echo "========================================"
            '''
        }
    }
}
    }

    post {
        always {
            archiveArtifacts(
                artifacts: 'reports/**',
                allowEmptyArchive: true,
                fingerprint: true
            )

            script {
                echo "Pipeline result: ${currentBuild.currentResult}"

                emailext(
                    to: "${NOTIFY_EMAIL}",
                    subject: "${currentBuild.currentResult}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                    body: """
Job: ${env.JOB_NAME}
Build: #${env.BUILD_NUMBER}
Result: ${currentBuild.currentResult}
Image: ${env.IMAGE_NAME ?: 'Not built'}:${env.IMAGE_TAG ?: 'N/A'}

Console output:
${env.BUILD_URL}console
"""
                )
            }
        }

        success {
            echo 'All pipeline stages completed, including production deployment.'
        }

        failure {
            echo 'Pipeline failed or a release gate rejected the build.'
        }

        cleanup {
            sh 'docker image prune -f || true'
        }
    }
}
