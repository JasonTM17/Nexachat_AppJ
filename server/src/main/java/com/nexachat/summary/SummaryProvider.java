package com.nexachat.summary;

public interface SummaryProvider {

    ProviderResult summarize(SummaryWindow window, SummaryLanguage language);

    String id();
}
