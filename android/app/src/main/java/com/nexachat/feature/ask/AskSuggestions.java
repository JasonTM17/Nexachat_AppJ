package com.nexachat.feature.ask;

import androidx.annotation.StringRes;
import com.nexachat.R;
import java.util.List;

/** Static starter questions shown before the first ask in a sheet session. */
final class AskSuggestions {
    static final List<Suggestion> STARTERS = List.of(
            new Suggestion(R.string.ask_starter_decisions, R.string.ask_suggestion_decide),
            new Suggestion(R.string.ask_starter_timing, R.string.ask_suggestion_when),
            new Suggestion(R.string.ask_starter_people, R.string.ask_suggestion_who),
            new Suggestion(R.string.ask_starter_next_steps, R.string.ask_suggestion_next));

    record Suggestion(@StringRes int label, @StringRes int question) {}

    private AskSuggestions() {
    }
}
