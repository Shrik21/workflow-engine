package com.orchpilot.workflow.plugins.azure;
final class AzureException extends RuntimeException{final String code;final boolean retryable;AzureException(String c,String m,boolean r){super(m);code=c;retryable=r;}}
