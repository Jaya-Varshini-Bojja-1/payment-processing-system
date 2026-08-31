package com.portfolio.paymentsystem.common.domain;

/**
 * V1 supports exactly one currency. Modeling it as an enum (rather than a
 * free-form string) means "is this INR?" is a compile-time-checked switch,
 * not a runtime string comparison scattered across the codebase — and
 * adding a second currency later is an additive enum change, not a search
 * for every place a currency string was compared.
 */
public enum Currency {
    INR
}
