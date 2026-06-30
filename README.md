# MSB E-Commerce Microservices

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2-green.svg)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-20-red.svg)](https://angular.io/)
[![Docker](https://img.shields.io/badge/Docker-Enabled-blue.svg)](https://www.docker.com/)

A comprehensive, production-grade e-commerce microservices application built with Java Spring Boot and Angular. This project represents a year of dedicated design and development, solving complex distributed system challenges to provide a robust, scalable backend and a modern, responsive frontend.

## 🚀 Overview

MSB E-Commerce is designed to demonstrate a real-world microservices architecture. It includes services for product management, order processing, inventory control, payment handling, and notifications, all orchestrated behind an API Gateway and secured with OAuth2/OpenID Connect.

### Key Features

- **Microservices Architecture**: Decoupled services for independent scaling and development.
- **API Gateway**: Centralized entry point with routing, rate limiting, and circuit breaking.
- **Security**: OAuth2 Resource Server implementation using **Keycloak** for identity management.
- **Event-Driven**: Asynchronous communication using **Apache Kafka** for order placement and notifications.
- **Resilience**: Implementation of Circuit Breaker pattern using **Resilience4j**.
- **Observability**: Distributed tracing and centralized logging (ready for integration).
- **Responsive Frontend**: Modern UI built with **Angular 20** and **TailwindCSS**.

## 🛠️ Tech Stack

### Backend

- **Language**: Java 21
- **Framework**: Spring Boot 3.4.2, Spring Cloud 2024.0.0
- **Database**:
  - PostgreSQL (Auth, Keycloak)
  - MongoDB (Product Service)
  - MySQL (Order, Inventory, Payment)
- **Messaging**: Apache Kafka
- **Security**: Spring Security, OAuth2, Keycloak
- **Build Tool**: Maven

### Frontend

- **Framework**: Angular 20
- **Styling**: TailwindCSS
- **State Management**: Signals (Angular Core)

### Infrastructure

- **Containerization**: Docker & Docker Compose
- **Service Discovery**: (Planned/Static)
- **API Gateway**: Spring Cloud Gateway MVC

## 🏗️ Architecture

The system consists of the following microservices:

| Service | Port | Description | DB |
|---|---:|---|---|
| API Gateway | 9000 | Entry point, routing, auth & resilience | - |
| Auth Service | 8085 | User authentication & registration | PostgreSQL |
| Product Service | 8080 | Product catalog management | MongoDB |
| Order Service | 8081 | Order lifecycle management | MySQL |
| Inventory Service | 8082 | Stock tracking and reservation | MySQL |
| Payment Service | 8084 | Payment processing | MySQL |
| Notification Service | 8083 | Email/SMS notifications via Kafka | - |
| Frontend | 4200 | User Interface | - |

## 🚦 Getting Started

### Prerequisites

- Java 21
- Node.js 20+ and npm
- Docker & Docker Compose
- Maven is provided by the repository wrapper

Windows PowerShell users should run `npm.cmd` if local execution policy blocks
`npm.ps1`.

Verify the local toolchain:

```powershell
java -version
.\mvnw.cmd -version
node --version
npm.cmd --version
docker compose version
docker compose config --quiet
```

### Installation

1. **Clone the repository**

   ```bash
   git clone https://github.com/Arkrly/msb-ecom.git
   cd msb-ecom
   ```

2. **Start Infrastructure (Databases, Broker, Keycloak)**

   ```bash
   ./run.sh local
   ```

   _The `run.sh` script automates the startup of Docker containers and local Java processes._

   Alternatively, use Docker Compose directly:

   ```bash
   docker-compose up -d
   ```

3. **Run Backend Services**
   If not using `run.sh`, you can run each service individually:

   ```bash
   cd product-service && ../mvnw spring-boot:run
   cd auth-service && ../mvnw spring-boot:run
   cd api-gateway && ../mvnw spring-boot:run
   ```

   `order-service`, `inventory-service`, `payment-service`, and
   `notification-service` are archived V2/tutorial stubs and are not part of
   the active MVP build.

4. **Run Frontend**
   ```bash
   cd frontend
   npm ci
   npm run start
   ```
   Access the app at `http://localhost:4200`.

### Verification

Backend:

```powershell
.\mvnw.cmd -DskipTests package
.\mvnw.cmd test
```

Frontend:

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run build
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

See [Phase 1 Toolchain and Build Baseline](docs/phase1-baseline.md) for the
latest verified results and known baseline warnings.

## 🤝 Contributing

Contributions are welcome! This project is open source to help others learn and build better software. Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct, and the process for submitting pull requests.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## ❤️ Acknowledgments

- Built with passion and coffee over 12 months.
- Open sourced for the community.
