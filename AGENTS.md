# Project Development Instructions

## Environment Constraint

Docker is currently unavailable because the development machine has only 4 GB RAM.

### Important

- Do NOT require Docker to run or develop this project.
- Do NOT make Docker a prerequisite for any milestone.
- Run the Spring Boot application directly on the host machine.
- Use locally installed infrastructure where required, such as PostgreSQL and Redis.
- Introduce Kafka only when the relevant milestone requires it.
- Keep the application architecture Docker-compatible so Docker can be added later without major code changes.
- Do not spend development time troubleshooting or configuring Docker unless explicitly requested.

## Current Development Approach

Prioritize completing the project functionality over Docker setup.

Introduce infrastructure incrementally:

1. Spring Boot
2. PostgreSQL when persistence is required
3. Redis when caching/idempotency/rate-limiting functionality requires it
4. Kafka when asynchronous/event-driven functionality requires it

Only introduce a dependency when the current milestone actually needs it.

## Architecture

- Do not simplify or redesign the intended architecture merely because Docker is unavailable.
- Keep the application structured so infrastructure can later be moved into Docker Compose with minimal or no application-code changes.
- Use configuration properties/environment variables for connection details rather than hardcoding environment-specific values.
- Follow the project's existing architecture and milestone plan unless a change is explicitly discussed.

## Development Principles

- Build incrementally, milestone by milestone.
- Keep each change focused on the current milestone.
- Prefer simple, production-oriented solutions over unnecessary complexity.
- Do not add technologies, libraries, or services unless they have a clear purpose in the current milestone.
- Preserve clean separation between business logic, persistence, messaging, and infrastructure concerns.
- Write code that is easy to test and explain in an interview.

## Current Priority

Continue with Milestone 0 (repository skeleton) and do not block progress because Docker is unavailable.
