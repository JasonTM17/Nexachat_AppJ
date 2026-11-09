package com.nexachat.poll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps {@code poll_options} (V15__group_polls.sql): one ordered choice of a
 * poll. {@code position} is the zero-based display order and is unique per
 * poll; labels are immutable for the poll's lifetime so existing votes never
 * point at reworded text.
 */
@Entity
@Table(name = "poll_options")
public class PollOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false, updatable = false)
    private Poll poll;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "label", nullable = false, length = 100, updatable = false)
    private String label;

    protected PollOption() {
        // JPA only
    }

    public static PollOption create(Poll poll, int position, String label) {
        PollOption option = new PollOption();
        option.poll = poll;
        option.position = position;
        option.label = label;
        return option;
    }

    public UUID getId() {
        return id;
    }

    public Poll getPoll() {
        return poll;
    }

    public int getPosition() {
        return position;
    }

    public String getLabel() {
        return label;
    }
}
