package com.nexachat.feature.contacts;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import com.google.android.material.appbar.AppBarLayout;
import java.util.List;

/**
 * Bounds the letter rail to the visible list viewport. The shared
 * scrolling-view behavior is built for scrolling children: it measures a
 * match_parent child taller than the screen and pins its top to the app bar's
 * bottom edge, so a non-scrolling overlay overflows the bottom — the lowest
 * letters, '#' included, cannot be reached while the header is expanded.
 * Instead the rail's top tracks the app bar's bottom edge and its bottom
 * stays at the parent's bottom edge at every app-bar offset.
 */
public final class ContactLetterRailBehavior
        extends CoordinatorLayout.Behavior<ContactLetterRailView> {

    public ContactLetterRailBehavior(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean layoutDependsOn(
            @NonNull CoordinatorLayout parent,
            @NonNull ContactLetterRailView child,
            @NonNull View dependency) {
        return dependency instanceof AppBarLayout;
    }

    @Override
    public boolean onMeasureChild(
            @NonNull CoordinatorLayout parent,
            @NonNull ContactLetterRailView child,
            int parentWidthMeasureSpec,
            int widthUsed,
            int parentHeightMeasureSpec,
            int heightUsed) {
        AppBarLayout appBar = findAppBar(parent, child);
        if (appBar == null) {
            return false;
        }
        int viewport = View.MeasureSpec.getSize(parentHeightMeasureSpec);
        int height = Math.max(0, viewport - railTop(appBar));
        parent.onMeasureChild(
                child,
                parentWidthMeasureSpec,
                widthUsed,
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                heightUsed);
        return true;
    }

    @Override
    public boolean onLayoutChild(
            @NonNull CoordinatorLayout parent,
            @NonNull ContactLetterRailView child,
            int layoutDirection) {
        AppBarLayout appBar = findAppBar(parent, child);
        if (appBar == null) {
            return false;
        }
        placeInViewport(parent, child, appBar.getBottom(), layoutDirection);
        return true;
    }

    @Override
    public boolean onDependentViewChanged(
            @NonNull CoordinatorLayout parent,
            @NonNull ContactLetterRailView child,
            @NonNull View dependency) {
        placeInViewport(
                parent,
                child,
                dependency.getBottom(),
                ViewCompat.getLayoutDirection(parent));
        return true;
    }

    /**
     * The row the rail's top edge follows: the app bar's current bottom. On
     * the first measure pass the app bar is measured but not laid out yet, so
     * its expanded height stands in for the (still zero) bottom bound.
     */
    private static int railTop(@NonNull AppBarLayout appBar) {
        return appBar.getBottom() > 0 ? appBar.getBottom() : appBar.getMeasuredHeight();
    }

    /**
     * Lays the rail out in [app-bar bottom, parent bottom]. The viewport
     * height changes with the app-bar offset without a new measure pass, so
     * the child is re-measured in place when its target height changed —
     * cells always distribute over the on-screen region.
     */
    private static void placeInViewport(
            @NonNull CoordinatorLayout parent,
            @NonNull ContactLetterRailView child,
            int top,
            int layoutDirection) {
        if (child.getVisibility() == View.GONE) {
            return;
        }
        CoordinatorLayout.LayoutParams lp =
                (CoordinatorLayout.LayoutParams) child.getLayoutParams();
        int bottom = parent.getHeight() - parent.getPaddingBottom();
        int height = Math.max(0, bottom - top - lp.topMargin - lp.bottomMargin);
        if (child.getMeasuredHeight() != height) {
            child.measure(
                    View.MeasureSpec.makeMeasureSpec(
                            child.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        }
        Rect available = new Rect(
                parent.getPaddingLeft() + lp.leftMargin,
                top + lp.topMargin,
                parent.getWidth() - parent.getPaddingRight() - lp.rightMargin,
                bottom - lp.bottomMargin);
        Rect out = new Rect();
        GravityCompat.apply(
                lp.gravity == Gravity.NO_GRAVITY
                        ? GravityCompat.END | Gravity.TOP
                        : lp.gravity,
                child.getMeasuredWidth(),
                child.getMeasuredHeight(),
                available,
                out,
                layoutDirection);
        child.layout(out.left, out.top, out.right, out.bottom);
    }

    @Nullable
    private static AppBarLayout findAppBar(
            @NonNull CoordinatorLayout parent, @NonNull View child) {
        List<View> dependencies = parent.getDependencies(child);
        for (int i = 0; i < dependencies.size(); i++) {
            View dependency = dependencies.get(i);
            if (dependency instanceof AppBarLayout) {
                return (AppBarLayout) dependency;
            }
        }
        return null;
    }
}
