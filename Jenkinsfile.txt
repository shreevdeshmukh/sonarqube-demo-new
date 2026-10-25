
pipeline {
    agent {
        label 'dockerhost'
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
        SONAR_PROJECT_KEY = 'sonarqube-demo'

        DOCKERHUB_CREDENTIALS = 'dockerhub-creds'
        DOCKERHUB_REPO = 'ninande/my-web-app'

        AWS_REGION = 'ap-south-1'
        AWS_ACCOUNT_ID = '699258777180'
        ECR_REPO = 'my-web-app'

        COMPOSE_DIR = '/opt/my-web-app'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm

                sh '''
                    set -eu
                    git log -1 --oneline
                    test -f pom.xml
                    test -f Dockerfile
                '''
            }
        }

        stage('Trivy Filesystem Scan') {
            steps {
                sh '''
                    set -eu
                    mkdir -p reports

                    trivy fs \
                      --scanners vuln,secret,misconfig \
                      --format json \
                      --output reports/trivy-fs-report.json \
                      .
                '''
            }
        }

        stage('Build and Test') {
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

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh '''
                        set -eu

                        mvn -B \
                          org.sonarsource.scanner.maven:sonar-maven-plugin:5.2.0.4988:sonar \
                          -Dsonar.projectKey="${SONAR_PROJECT_KEY}" \
                          -Dsonar.projectName="SonarQube-Demo" \
                          -Dsonar.host.url="${SONAR_HOST_URL}"
                    '''
                }
            }
        }

        stage('Select Registry and Build Image') {
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

                    echo "Building ${IMAGE_NAME}:${IMAGE_TAG}"

                    docker build \
                      -t "${IMAGE_NAME}:${IMAGE_TAG}" \
                      -t "${IMAGE_NAME}:latest" \
                      .
                '''
            }
        }

        stage('Trivy Image Scan') {
            steps {
                sh '''
                    set -eu
                    mkdir -p reports

                    trivy image \
                      --severity HIGH,CRITICAL \
                      --ignore-unfixed \
                      --format json \
                      --output reports/trivy-image-report.json \
                      "${IMAGE_NAME}:${IMAGE_TAG}"

                    echo "Image scan report generated."
                '''
            }
        }

        stage('Push to Registry') {
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

        stage('Deploy with Docker Compose') {
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
    }

    post {
        always {
            archiveArtifacts(
                artifacts: 'reports/**',
                allowEmptyArchive: true,
                fingerprint: true
            )

            echo "Pipeline result: ${currentBuild.currentResult}"
        }

        success {
            echo 'Build, tests, scans, publishing, and deployment completed successfully.'
        }

        failure {
            echo 'Pipeline failed. Check Console Output and archived reports.'
        }

        cleanup {
            sh 'docker image prune -f || true'
        }
    }
}
