
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

        ANSIBLE_HOST = '10.0.11.194'
        ANSIBLE_USER = 'ansadmin'
        ANSIBLE_INVENTORY = '/opt/ansible/my-web-app/inventory.ini'
        ANSIBLE_PLAYBOOK = '/opt/ansible/my-web-app/deploy.yml'
        ANSIBLE_CREDENTIALS = 'ansible-control-ssh-key'

        NOTIFY_EMAIL = 'ninad.de1105@gmail.com'
    }

    stages {
        stage('1. GitHub Push and Checkout') {
            steps {
                checkout scm

                sh '''
                    set -eu
                    git log -1 --oneline
                    test -f pom.xml
                    test -f Dockerfile
                    test -f ansible/inventory.ini
                    test -f ansible/deploy.yml
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

        stage('3. Code Analysis - SonarQube Gate') {
            steps {
                withSonarQubeEnv('SonarQube') {
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

        stage('4. Build Imange and Image Scan - Trivy Security Gate') {
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

                    trivy fs \
                      --scanners vuln,secret,misconfig \
                      --format json \
                      --output reports/trivy-fs-report.json \
                      .

                    docker build \
                      -t "${IMAGE_NAME}:${IMAGE_TAG}" \
                      -t "${IMAGE_NAME}:latest" \
                      .

                    trivy image \
                      --severity CRITICAL \
                      --ignore-unfixed \
                      --exit-code 1 \
                      --format json \
                      --output reports/trivy-image-report.json \
                      "${IMAGE_NAME}:${IMAGE_TAG}"

                    echo 'Trivy Gate passed: no matching critical vulnerabilities.'
                '''
            }
        }

        stage('5. Package Image - Push to Registry') {
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

        stage('6. Deploy to Dev and Staging') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: "${ANSIBLE_CREDENTIALS}",
                        keyFileVariable: 'ANSIBLE_KEY'
                    )
                ]) {
                    sh '''
                        set -eu

                        ansible-playbook \
                          -i "${ANSIBLE_INVENTORY}" \
                          "${ANSIBLE_PLAYBOOK}" \
                          --private-key "$ANSIBLE_KEY" \
                          --limit dev \
                          -e "image_name=${IMAGE_NAME}" \
                          -e "image_tag=${IMAGE_TAG}" \
                          -e "registry=${REGISTRY}" \
                          -e "aws_region=${AWS_REGION}" \
                          -e "aws_account_id=${AWS_ACCOUNT_ID}"

                        ansible-playbook \
                          -i "${ANSIBLE_INVENTORY}" \
                          "${ANSIBLE_PLAYBOOK}" \
                          --private-key "$ANSIBLE_KEY" \
                          --limit staging \
                          -e "image_name=${IMAGE_NAME}" \
                          -e "image_tag=${IMAGE_TAG}" \
                          -e "registry=${REGISTRY}" \
                          -e "aws_region=${AWS_REGION}" \
                          -e "aws_account_id=${AWS_ACCOUNT_ID}"
                    '''
                }
            }
        }

        stage('7. Manual Approval - Production Gate') {
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

        stage('8. Production Deployment') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: "${ANSIBLE_CREDENTIALS}",
                        keyFileVariable: 'ANSIBLE_KEY'
                    )
                ]) {
                    sh '''
                        set -eu

                        ansible-playbook \
                          -i "${ANSIBLE_INVENTORY}" \
                          "${ANSIBLE_PLAYBOOK}" \
                          --private-key "$ANSIBLE_KEY" \
                          --limit prod \
                          -e "image_name=${IMAGE_NAME}" \
                          -e "image_tag=${IMAGE_TAG}" \
                          -e "registry=${REGISTRY}" \
                          -e "aws_region=${AWS_REGION}" \
                          -e "aws_account_id=${AWS_ACCOUNT_ID}"
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
            echo 'All gates passed and production deployment completed.'
        }

        failure {
            echo 'Pipeline failed or a release gate rejected the build.'
        }

        cleanup {
            sh 'docker image prune -f || true'
        }
    }
}
