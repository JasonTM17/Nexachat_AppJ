"""Requirement-driven 4-tier E2E test suite for NexaChat Social Network.

Disjoint from product source directories (site/, server/, admin-web/, android/).
Uses tools/e2e/disposable_stack.py for isolated PostgreSQL + Spring Boot
provisioning and an in-process vercel.json-faithful reverse proxy.
"""

__all__ = [
    "browser_driver",
    "client",
    "fixtures",
    "proxy_server",
    "run_social_e2e",
    "stack_harness",
    "tier1_feature_api",
    "tier2_boundary_edge",
    "tier3_cross_feature",
    "tier4_browser_e2e",
]
