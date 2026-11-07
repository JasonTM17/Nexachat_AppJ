"""Generate B1 ViewBinding-friendly screen layouts."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res/layout"
LAND = Path(__file__).resolve().parents[1] / "app/src/main/res/layout-land"


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.strip() + "\n", encoding="utf-8")


TOOLBAR = """
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorSurface"
        app:layout_scrollFlags="scroll|enterAlways" />
"""

SCAFFOLD_HEADER = """<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground">

    <com.google.android.material.appbar.AppBarLayout
        android:id="@+id/appBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content">
{toolbar}
    </com.google.android.material.appbar.AppBarLayout>
"""

STATE_STUBS = """
    <com.nexachat.core.ui.components.LoadingStateView
        android:id="@+id/loadingState"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

    <com.nexachat.core.ui.components.ErrorStateView
        android:id="@+id/errorState"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

    <com.nexachat.core.ui.components.EmptyStateView
        android:id="@+id/emptyState"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />
"""


def main() -> None:
    write(ROOT / "activity_nexa_host.xml", """
<?xml version="1.0" encoding="utf-8"?>
<androidx.fragment.app.FragmentContainerView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_host"
    android:name="androidx.navigation.fragment.NavHostFragment"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:defaultNavHost="true"
    app:navGraph="@navigation/nav_main" />
""")

    write(ROOT / "fragment_splash.xml", """
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorPrimary">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:gravity="center"
        android:orientation="vertical">

        <ImageView
            android:id="@+id/splashLogo"
            android:layout_width="72dp"
            android:layout_height="72dp"
            android:contentDescription="@string/app_name"
            android:src="@drawable/ic_forum_outline"
            app:tint="?attr/colorOnPrimary"
            xmlns:app="http://schemas.android.com/apk/res-auto" />

        <TextView
            android:id="@+id/splashTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/space_md"
            android:text="@string/app_name"
            android:textAppearance="?attr/textAppearanceHeadlineMedium"
            android:textColor="?attr/colorOnPrimary" />

        <com.google.android.material.progressindicator.CircularProgressIndicator
            android:id="@+id/splashProgress"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/space_lg"
            android:indeterminate="true" />
    </LinearLayout>
</FrameLayout>
""")

    auth_form = """
        <ScrollView
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:fillViewport="true">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_horizontal"
                android:orientation="vertical"
                android:padding="@dimen/space_lg">

                <LinearLayout
                    android:id="@+id/contentColumn"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:maxWidth="@dimen/auth_form_max_width"
                    android:orientation="vertical"
                    android:gravity="center_horizontal">

                    <ImageView
                        android:id="@+id/authLogo"
                        android:layout_width="64dp"
                        android:layout_height="64dp"
                        android:background="@drawable/bg_auth_logo"
                        android:contentDescription="@string/app_name"
                        android:padding="14dp"
                        android:src="@drawable/ic_forum_outline" />

                    <TextView
                        android:id="@+id/authTitle"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="@dimen/space_md"
                        android:text="@string/app_name"
                        android:textAppearance="?attr/textAppearanceHeadlineLarge"
                        android:textColor="?attr/colorPrimary" />

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/phoneLayout"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="@dimen/space_xl"
                        android:hint="@string/auth_phone_label">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/phoneInput"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="phone" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/passwordLayout"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="@dimen/space_sm"
                        android:hint="@string/auth_password_label"
                        app:endIconDrawable="@drawable/ic_visibility"
                        app:endIconContentDescription="@string/cd_password_visibility"
                        app:endIconMode="password_toggle"
                        xmlns:app="http://schemas.android.com/apk/res-auto">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/passwordInput"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="textPassword" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <TextView
                        android:id="@+id/errorBanner"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="@dimen/space_sm"
                        android:background="?attr/colorErrorContainer"
                        android:padding="@dimen/space_sm"
                        android:textColor="?attr/colorOnErrorContainer"
                        android:visibility="gone" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/primaryAction"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="@dimen/space_md"
                        android:minHeight="@dimen/min_touch_target" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/secondaryAction"
                        style="@style/Widget.Material3.Button.TextButton"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:minHeight="@dimen/min_touch_target" />
                </LinearLayout>
            </LinearLayout>
        </ScrollView>
"""

    write(ROOT / "fragment_login.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground">
{auth_form}
{STATE_STUBS}
</FrameLayout>
""")

    write(ROOT / "fragment_register.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground">
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:fillViewport="true">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_horizontal"
            android:orientation="vertical"
            android:padding="@dimen/space_lg">
            <LinearLayout
                android:id="@+id/registerColumn"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:maxWidth="@dimen/auth_form_max_width"
                android:orientation="vertical">
                <com.google.android.material.textfield.TextInputLayout
                    android:id="@+id/nameLayout"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:hint="@string/auth_display_name_label">
                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/nameInput"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="textPersonName" />
                </com.google.android.material.textfield.TextInputLayout>
                <com.google.android.material.textfield.TextInputLayout
                    android:id="@+id/phoneLayout"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/space_sm"
                    android:hint="@string/auth_phone_label">
                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/phoneInput"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="phone" />
                </com.google.android.material.textfield.TextInputLayout>
                <com.google.android.material.textfield.TextInputLayout
                    android:id="@+id/passwordLayout"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/space_sm"
                    android:hint="@string/auth_password_label"
                    app:endIconContentDescription="@string/cd_password_visibility"
                    app:endIconDrawable="@drawable/ic_visibility"
                    app:endIconMode="password_toggle">
                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/passwordInput"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="textPassword" />
                </com.google.android.material.textfield.TextInputLayout>
                <com.google.android.material.textfield.TextInputLayout
                    android:id="@+id/confirmPasswordLayout"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/space_sm"
                    android:hint="@string/auth_confirm_password_label">
                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/confirmPasswordInput"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="textPassword" />
                </com.google.android.material.textfield.TextInputLayout>
                <com.google.android.material.button.MaterialButton
                    android:id="@+id/registerButton"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/space_md"
                    android:minHeight="@dimen/min_touch_target"
                    android:text="@string/action_create_account" />
            </LinearLayout>
        </LinearLayout>
    </ScrollView>
{STATE_STUBS}
</FrameLayout>
""")

    write(ROOT / "fragment_inbox.xml", f"""<?xml version="1.0" encoding="utf-8"?>
{SCAFFOLD_HEADER.format(toolbar=TOOLBAR + '''
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="end"
            android:orientation="horizontal"
            android:paddingHorizontal="@dimen/space_sm">
            <ImageButton
                android:id="@+id/contactsButton"
                android:layout_width="@dimen/min_touch_target"
                android:layout_height="@dimen/min_touch_target"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/contacts_title"
                android:src="@drawable/ic_people" />
            <ImageButton
                android:id="@+id/profileButton"
                android:layout_width="@dimen/min_touch_target"
                android:layout_height="@dimen/min_touch_target"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/profile_title"
                android:src="@drawable/ic_account_circle" />
        </LinearLayout>''')}
    <androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        android:id="@+id/swipeRefresh"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior"
        xmlns:app="http://schemas.android.com/apk/res-auto">
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/conversationList"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:padding="@dimen/space_sm" />
    </androidx.swiperefreshlayout.widget.SwipeRefreshLayout>
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/createGroupFab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="@dimen/space_lg"
        android:contentDescription="@string/group_create_title"
        app:srcCompat="@drawable/ic_add"
        xmlns:app="http://schemas.android.com/apk/res-auto" />
{STATE_STUBS}
</androidx.coordinatorlayout.widget.CoordinatorLayout>
""")

    write(ROOT / "item_inbox_conversation.xml", """
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginVertical="@dimen/space_xs"
    app:cardElevation="@dimen/elevation_level1">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:minHeight="@dimen/min_touch_target"
        android:padding="@dimen/space_md">

        <com.nexachat.core.ui.components.AvatarView
            android:id="@+id/avatar"
            android:layout_width="48dp"
            android:layout_height="48dp"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="@dimen/space_md"
            android:textAppearance="?attr/textAppearanceTitleMedium"
            app:layout_constraintEnd_toStartOf="@id/time"
            app:layout_constraintStart_toEndOf="@id/avatar"
            app:layout_constraintTop_toTopOf="@id/avatar" />

        <TextView
            android:id="@+id/time"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBodySmall"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="@id/title" />

        <TextView
            android:id="@+id/preview"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/space_xs"
            android:ellipsize="end"
            android:maxLines="2"
            android:textAppearance="?attr/textAppearanceBodyMedium"
            android:textColor="?attr/colorOnSurfaceVariant"
            app:layout_constraintEnd_toStartOf="@id/unreadBadge"
            app:layout_constraintStart_toStartOf="@id/title"
            app:layout_constraintTop_toBottomOf="@id/title" />

        <TextView
            android:id="@+id/unreadBadge"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="@drawable/bg_unread_badge"
            android:paddingHorizontal="@dimen/space_sm"
            android:paddingVertical="2dp"
            android:textAppearance="?attr/textAppearanceLabelSmall"
            android:textColor="?attr/colorOnPrimary"
            android:visibility="gone"
            app:layout_constraintBottom_toBottomOf="@id/preview"
            app:layout_constraintEnd_toEndOf="parent" />
    </androidx.constraintlayout.widget.ConstraintLayout>
</com.google.android.material.card.MaterialCardView>
""")

    # Additional layouts: contacts, search, groups, chat shell, bubbles, etc.
    chat_shell = open(Path(__file__).parent / "gen_b1_chat_templates.txt", encoding="utf-8").read() if (Path(__file__).parent / "gen_b1_chat_templates.txt").exists() else ""
    if not chat_shell:
        chat_shell_path = ROOT.parent  # fallback inline below

    write(ROOT / "fragment_contacts.xml", f"""<?xml version="1.0" encoding="utf-8"?>
{SCAFFOLD_HEADER.format(toolbar='''
        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:navigationIcon="@drawable/ic_arrow_back"
            app:navigationContentDescription="@string/cd_back" />
        <com.google.android.material.button.MaterialButton
            android:id="@+id/searchButton"
            style="@style/Widget.Material3.Button.IconButton"
            android:layout_width="@dimen/min_touch_target"
            android:layout_height="@dimen/min_touch_target"
            android:contentDescription="@string/search_title"
            app:icon="@drawable/ic_search" />''')}
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/contactList"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior"
        xmlns:app="http://schemas.android.com/apk/res-auto" />
{STATE_STUBS}
</androidx.coordinatorlayout.widget.CoordinatorLayout>
""")

    write(ROOT / "item_contact.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:minHeight="@dimen/min_touch_target"
    android:orientation="horizontal"
    android:paddingHorizontal="@dimen/space_md"
    android:paddingVertical="@dimen/space_sm">

    <com.nexachat.core.ui.components.AvatarView
        android:id="@+id/avatar"
        android:layout_width="44dp"
        android:layout_height="44dp" />

    <TextView
        android:id="@+id/name"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/space_md"
        android:layout_weight="1"
        android:textAppearance="?attr/textAppearanceTitleMedium" />
</LinearLayout>
""")

    write(ROOT / "fragment_user_search.xml", f"""<?xml version="1.0" encoding="utf-8"?>
{SCAFFOLD_HEADER.format(toolbar='''
        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:navigationIcon="@drawable/ic_arrow_back"
            app:navigationContentDescription="@string/cd_back" />''')}
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        app:layout_behavior="@string/appbar_scrolling_view_behavior"
        xmlns:app="http://schemas.android.com/apk/res-auto">
        <com.google.android.material.textfield.TextInputLayout
            android:id="@+id/searchLayout"
            style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_margin="@dimen/space_md"
            android:hint="@string/search_label"
            app:startIconDrawable="@drawable/ic_search">
            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/searchInput"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:imeOptions="actionSearch"
                android:inputType="text" />
        </com.google.android.material.textfield.TextInputLayout>
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/resultsList"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1" />
    </LinearLayout>
{STATE_STUBS}
</androidx.coordinatorlayout.widget.CoordinatorLayout>
""")

    write(ROOT / "fragment_create_group.xml", f"""<?xml version="1.0" encoding="utf-8"?>
{SCAFFOLD_HEADER.format(toolbar='''
        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:navigationIcon="@drawable/ic_arrow_back"
            app:navigationContentDescription="@string/cd_back" />''')}
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior"
        xmlns:app="http://schemas.android.com/apk/res-auto">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="@dimen/space_md">
            <com.google.android.material.textfield.TextInputLayout
                android:id="@+id/groupNameLayout"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="@string/group_name_label">
                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/groupNameInput"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content" />
            </com.google.android.material.textfield.TextInputLayout>
            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/memberPickerList"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:nestedScrollingEnabled="false" />
            <com.google.android.material.button.MaterialButton
                android:id="@+id/createButton"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/space_md"
                android:minHeight="@dimen/min_touch_target"
                android:text="@string/action_create_group" />
        </LinearLayout>
    </ScrollView>
{STATE_STUBS}
</androidx.coordinatorlayout.widget.CoordinatorLayout>
""")

    write(ROOT / "fragment_group_detail.xml", f"""<?xml version="1.0" encoding="utf-8"?>
{SCAFFOLD_HEADER.format(toolbar='''
        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:navigationIcon="@drawable/ic_arrow_back"
            app:navigationContentDescription="@string/cd_back" />''')}
    <androidx.core.widget.NestedScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior"
        xmlns:app="http://schemas.android.com/apk/res-auto">
        <LinearLayout
            android:id="@+id/detailColumn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_horizontal"
            android:orientation="vertical"
            android:padding="@dimen/space_md">
            <com.nexachat.core.ui.components.AvatarView
                android:id="@+id/groupAvatar"
                android:layout_width="88dp"
                android:layout_height="88dp" />
            <TextView
                android:id="@+id/groupTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/space_sm"
                android:textAppearance="?attr/textAppearanceHeadlineSmall" />
            <com.nexachat.core.ui.components.SectionHeaderView
                android:id="@+id/membersHeader"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/space_lg" />
            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/memberList"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:nestedScrollingEnabled="false" />
        </LinearLayout>
    </androidx.core.widget.NestedScrollView>
{STATE_STUBS}
</androidx.coordinatorlayout.widget.CoordinatorLayout>
""")

    write(ROOT / "item_group_member.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:minHeight="@dimen/min_touch_target"
    android:orientation="horizontal"
    android:padding="@dimen/space_md">
    <com.nexachat.core.ui.components.AvatarView
        android:id="@+id/avatar"
        android:layout_width="40dp"
        android:layout_height="40dp" />
    <TextView
        android:id="@+id/memberName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/space_md"
        android:layout_weight="1"
        android:textAppearance="?attr/textAppearanceBodyLarge" />
    <ImageButton
        android:id="@+id/removeMember"
        android:layout_width="@dimen/min_touch_target"
        android:layout_height="@dimen/min_touch_target"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/action_delete"
        android:src="@drawable/ic_delete" />
</LinearLayout>
""")

    _write_chat_layouts()
    _write_misc_layouts()
    print("layouts generated")


def _write_chat_layouts() -> None:
    chat = """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/chatRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground">

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/chatToolbar"
        android:layout_width="0dp"
        android:layout_height="?attr/actionBarSize"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:navigationContentDescription="@string/cd_back"
        app:navigationIcon="@drawable/ic_arrow_back" />

    <HorizontalScrollView
        android:id="@+id/chatToolbarActions"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:scrollbars="none"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/chatToolbar">
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal">
            <ImageButton android:id="@+id/callButton" android:layout_width="@dimen/min_touch_target" android:layout_height="@dimen/min_touch_target" android:contentDescription="@string/cd_call" android:src="@drawable/ic_call" android:background="?attr/selectableItemBackgroundBorderless"/>
            <ImageButton android:id="@+id/searchInChatButton" android:layout_width="@dimen/min_touch_target" android:layout_height="@dimen/min_touch_target" android:contentDescription="@string/cd_search_in_chat" android:src="@drawable/ic_search" android:background="?attr/selectableItemBackgroundBorderless"/>
            <ImageButton android:id="@+id/infoButton" android:layout_width="@dimen/min_touch_target" android:layout_height="@dimen/min_touch_target" android:contentDescription="@string/cd_group_info" android:src="@drawable/ic_info" android:background="?attr/selectableItemBackgroundBorderless"/>
            <ImageButton android:id="@+id/summaryButton" android:layout_width="@dimen/min_touch_target" android:layout_height="@dimen/min_touch_target" android:contentDescription="@string/summary_title" android:src="@drawable/ic_auto_awesome" android:background="?attr/selectableItemBackgroundBorderless"/>
            <ImageButton android:id="@+id/moreButton" android:layout_width="@dimen/min_touch_target" android:layout_height="@dimen/min_touch_target" android:contentDescription="@string/action_more_options" android:src="@drawable/ic_more_vert" android:background="?attr/selectableItemBackgroundBorderless"/>
        </LinearLayout>
    </HorizontalScrollView>

    <include
        android:id="@+id/pinnedBanner"
        layout="@layout/include_chat_pinned_banner"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/chatToolbarActions" />

    <include
        android:id="@+id/replyEditBanner"
        layout="@layout/include_chat_reply_edit_banner"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/pinnedBanner" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/messageList"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:clipToPadding="false"
        android:paddingHorizontal="@dimen/space_sm"
        android:paddingBottom="@dimen/space_sm"
        app:layout_constraintBottom_toTopOf="@id/composerBar"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/replyEditBanner" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/scrollDownFab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="@dimen/space_md"
        android:contentDescription="@string/cd_scroll_to_bottom"
        android:visibility="gone"
        app:layout_constraintBottom_toTopOf="@id/composerBar"
        app:layout_constraintEnd_toEndOf="parent"
        app:srcCompat="@drawable/ic_keyboard_arrow_down" />

    <include
        android:id="@+id/composerBar"
        layout="@layout/include_chat_composer_bar"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <com.nexachat.core.ui.components.LoadingStateView android:id="@+id/loadingState" android:layout_width="0dp" android:layout_height="0dp" android:visibility="gone" app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintEnd_toEndOf="parent" app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent"/>
    <com.nexachat.core.ui.components.ErrorStateView android:id="@+id/errorState" android:layout_width="0dp" android:layout_height="0dp" android:visibility="gone" app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintEnd_toEndOf="parent" app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent"/>
</androidx.constraintlayout.widget.ConstraintLayout>
"""
    write(ROOT / "fragment_chat.xml", chat)
    write(LAND / "fragment_chat.xml", chat.replace(
        'android:id="@+id/chatRoot"',
        'android:id="@+id/chatRoot" android:paddingHorizontal="@dimen/space_lg"',
    ).replace(
        'app:layout_constraintStart_toStartOf="parent"',
        'app:layout_constraintStart_toStartOf="parent" android:layout_marginStart="0dp"',
    ))

    write(ROOT / "include_chat_composer_bar.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/composerRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/colorSurface"
    android:elevation="@dimen/elevation_level3"
    android:gravity="center_vertical"
    android:minHeight="@dimen/min_touch_target"
    android:orientation="horizontal"
    android:padding="@dimen/space_sm">

    <ImageButton
        android:id="@+id/attachButton"
        android:layout_width="@dimen/min_touch_target"
        android:layout_height="@dimen/min_touch_target"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/cd_attach"
        android:src="@drawable/ic_attach_file" />

    <com.google.android.material.textfield.TextInputEditText
        android:id="@+id/messageInput"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="@dimen/space_sm"
        android:layout_weight="1"
        android:background="@drawable/bg_composer_pill"
        android:hint="@string/chat_input_hint"
        android:minHeight="@dimen/min_touch_target"
        android:paddingHorizontal="@dimen/space_md" />

    <ImageButton
        android:id="@+id/sendButton"
        android:layout_width="@dimen/min_touch_target"
        android:layout_height="@dimen/min_touch_target"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/cd_send"
        android:src="@drawable/ic_send" />
</LinearLayout>
""")

    write(ROOT / "include_chat_reply_edit_banner.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/replyEditRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/colorSurfaceContainerHigh"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:padding="@dimen/space_sm"
    android:visibility="gone">
    <ImageView android:id="@+id/replyIcon" android:layout_width="24dp" android:layout_height="24dp" android:contentDescription="@string/action_reply" android:src="@drawable/ic_reply"/>
    <TextView android:id="@+id/replyPreview" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_marginStart="@dimen/space_sm" android:layout_weight="1" android:ellipsize="end" android:maxLines="2"/>
    <ImageButton android:id="@+id/cancelReplyEdit" android:layout_width="@dimen/min_touch_target" android:layout_height="@dimen/min_touch_target" android:background="?attr/selectableItemBackgroundBorderless" android:contentDescription="@string/cd_cancel_reply" android:src="@drawable/ic_close"/>
</LinearLayout>
""")

    write(ROOT / "include_chat_pinned_banner.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/pinnedRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/colorPrimaryContainer"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:padding="@dimen/space_sm"
    android:visibility="gone">
    <ImageView android:layout_width="24dp" android:layout_height="24dp" android:contentDescription="@string/pinned_title" android:src="@drawable/ic_push_pin"/>
    <TextView android:id="@+id/pinnedText" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_marginStart="@dimen/space_sm" android:layout_weight="1" android:ellipsize="end" android:maxLines="2"/>
</LinearLayout>
""")

    write(ROOT / "include_chat_overlays.xml", """
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android">
    <include layout="@layout/bottom_sheet_attachment_picker" />
    <include layout="@layout/include_reaction_picker" />
    <include layout="@layout/bottom_sheet_summary" />
    <include layout="@layout/include_unread_banner" />
    <include layout="@layout/dialog_forward" />
</merge>
""")

    bubbles = [
        ("item_message_text_incoming", True, "text"),
        ("item_message_text_outgoing", False, "text"),
        ("item_message_image_incoming", True, "image"),
        ("item_message_image_outgoing", False, "image"),
        ("item_message_video_incoming", True, "video"),
        ("item_message_video_outgoing", False, "video"),
        ("item_message_audio_incoming", True, "audio"),
        ("item_message_audio_outgoing", False, "audio"),
        ("item_message_file_incoming", True, "file"),
        ("item_message_file_outgoing", False, "file"),
        ("item_message_system", True, "system"),
    ]
    for name, incoming, kind in bubbles:
        align = "start" if incoming else "end"
        bg = "@color/bubble_other" if incoming else "@color/bubble_mine"
        write(ROOT / f"{name}.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingVertical="@dimen/space_xs">
    <LinearLayout
        android:id="@+id/bubbleContainer"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@drawable/bg_message_bubble"
        android:backgroundTint="{bg}"
        android:maxWidth="@dimen/bubble_max_width"
        android:orientation="vertical"
        android:padding="@dimen/space_sm"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintHorizontal_bias="{"0" if incoming else "1"}"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent">
        <include android:id="@+id/replyQuote" layout="@layout/include_message_reply_quote" android:visibility="gone"/>
        <TextView android:id="@+id/messageBody" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textAppearance="?attr/textAppearanceBodyMedium" android:visibility="{"visible" if kind=="text" else "gone"}"/>
        <ImageView android:id="@+id/messageImage" android:layout_width="220dp" android:layout_height="160dp" android:contentDescription="@string/cd_open_attachment" android:scaleType="centerCrop" android:visibility="{"visible" if kind=="image" else "gone"}"/>
        <ImageButton android:id="@+id/playButton" android:layout_width="@dimen/min_touch_target" android:layout_height="@dimen/min_touch_target" android:contentDescription="@string/cd_open_attachment" android:src="@drawable/ic_play_arrow" android:visibility="{"visible" if kind in ("video","audio") else "gone"}"/>
        <TextView android:id="@+id/fileName" android:layout_width="wrap_content" android:layout_height="wrap_content" android:drawableStart="@drawable/ic_description" android:gravity="center_vertical" android:visibility="{"visible" if kind=="file" else "gone"}"/>
        <TextView android:id="@+id/systemLabel" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textAppearance="?attr/textAppearanceBodySmall" android:textColor="?attr/colorOnSurfaceVariant" android:visibility="{"visible" if kind=="system" else "gone"}"/>
        <include android:id="@+id/reactionsRow" layout="@layout/include_message_reactions_row"/>
    </LinearLayout>
</androidx.constraintlayout.widget.ConstraintLayout>
""")

    write(ROOT / "include_message_reply_quote.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_reply_quote"
    android:orientation="vertical"
    android:padding="@dimen/space_xs">
    <TextView android:id="@+id/replyAuthor" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textAppearance="?attr/textAppearanceLabelSmall" android:textColor="?attr/colorPrimary"/>
    <TextView android:id="@+id/replySnippet" android:layout_width="wrap_content" android:layout_height="wrap_content" android:ellipsize="end" android:maxLines="2" android:textAppearance="?attr/textAppearanceBodySmall"/>
</LinearLayout>
""")

    write(ROOT / "include_message_reactions_row.xml", """
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.chip.ChipGroup xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/reactionChipGroup"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/space_xs"
    app:singleLine="true" />
""")


def _write_misc_layouts() -> None:
    write(ROOT / "fragment_media_viewer.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black">
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/viewerToolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="#80000000"
        app:navigationContentDescription="@string/cd_back"
        app:navigationIcon="@drawable/ic_arrow_back"
        app:titleTextColor="@android:color/white" />
    <ImageView
        android:id="@+id/mediaImage"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:contentDescription="@string/cd_open_attachment"
        android:scaleType="fitCenter" />
</FrameLayout>
""")

    write(ROOT / "fragment_profile.xml", """
<?xml version="1.0" encoding="utf-8"?>
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <LinearLayout
        android:id="@+id/profileColumn"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_horizontal"
        android:orientation="vertical"
        android:padding="@dimen/space_md">
        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/profileToolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:navigationContentDescription="@string/cd_back"
            app:navigationIcon="@drawable/ic_arrow_back" />
        <com.nexachat.core.ui.components.AvatarView
            android:id="@+id/profileAvatar"
            android:layout_width="96dp"
            android:layout_height="96dp" />
        <TextView android:id="@+id/profileName" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="@dimen/space_sm" android:textAppearance="?attr/textAppearanceHeadlineSmall"/>
        <com.nexachat.core.ui.components.SettingsRowView android:id="@+id/languageRow" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <com.nexachat.core.ui.components.SettingsRowView android:id="@+id/themeRow" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <com.nexachat.core.ui.components.SettingsRowView android:id="@+id/logoutRow" android:layout_width="match_parent" android:layout_height="wrap_content"/>
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
""")

    write(ROOT / "fragment_language_settings.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/languageToolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        app:navigationContentDescription="@string/cd_back"
        app:navigationIcon="@drawable/ic_arrow_back"
        app:title="@string/settings_language" />
    <RadioGroup
        android:id="@+id/languageOptions"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="@dimen/space_md" />
</LinearLayout>
""")

    write(ROOT / "bottom_sheet_summary.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/summarySheetRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="@dimen/space_md">
    <TextView android:id="@+id/summaryTitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:drawableStart="@drawable/ic_auto_awesome" android:gravity="center_vertical" android:text="@string/summary_title" android:textAppearance="?attr/textAppearanceTitleLarge"/>
    <include android:id="@+id/summarySkeleton" layout="@layout/include_summary_skeleton" android:visibility="gone"/>
    <TextView android:id="@+id/summaryBody" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="@dimen/space_sm" android:textAppearance="?attr/textAppearanceBodyMedium"/>
    <include android:id="@+id/summaryError" layout="@layout/include_summary_error" android:visibility="gone"/>
</LinearLayout>
""")

    write(ROOT / "include_summary_skeleton.xml", """
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.progressindicator.LinearProgressIndicator xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/summarySkeletonBar"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:indeterminate="true" />
""")

    write(ROOT / "include_summary_error.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="@dimen/space_md">
    <ImageView android:layout_width="48dp" android:layout_height="48dp" android:contentDescription="@string/summary_error_generic" android:src="@drawable/ic_error_outline"/>
    <TextView android:id="@+id/summaryErrorText" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textAppearance="?attr/textAppearanceBodyMedium"/>
    <com.google.android.material.button.MaterialButton android:id="@+id/summaryRetry" android:layout_width="wrap_content" android:layout_height="wrap_content" android:minHeight="@dimen/min_touch_target" android:text="@string/action_retry"/>
</LinearLayout>
""")

    write(ROOT / "include_unread_banner.xml", """
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/unreadBanner"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="@dimen/space_sm"
    android:visibility="gone">
    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical" android:orientation="horizontal" android:padding="@dimen/space_sm">
        <ImageView android:layout_width="24dp" android:layout_height="24dp" android:contentDescription="@string/summary_title" android:src="@drawable/ic_auto_awesome"/>
        <TextView android:id="@+id/unreadBannerText" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_marginStart="@dimen/space_sm" android:layout_weight="1" android:text="@string/unread_banner_format"/>
        <ImageButton android:id="@+id/dismissUnreadBanner" android:layout_width="@dimen/min_touch_target" android:layout_height="@dimen/min_touch_target" android:background="?attr/selectableItemBackgroundBorderless" android:contentDescription="@string/unread_banner_dismiss" android:src="@drawable/ic_close"/>
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
""")

    write(ROOT / "dialog_forward.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/forwardDialogRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="@dimen/space_md">
    <TextView android:id="@+id/forwardTitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="@string/forward_title" android:textAppearance="?attr/textAppearanceTitleLarge"/>
    <com.google.android.material.textfield.TextInputLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:hint="@string/forward_search_placeholder" app:startIconDrawable="@drawable/ic_search">
        <com.google.android.material.textfield.TextInputEditText android:id="@+id/forwardSearch" android:layout_width="match_parent" android:layout_height="wrap_content"/>
    </com.google.android.material.textfield.TextInputLayout>
    <androidx.recyclerview.widget.RecyclerView android:id="@+id/forwardList" android:layout_width="match_parent" android:layout_height="200dp"/>
    <com.google.android.material.button.MaterialButton android:id="@+id/forwardSend" android:layout_width="match_parent" android:layout_height="wrap_content" android:minHeight="@dimen/min_touch_target" android:text="@string/forward_send"/>
</LinearLayout>
""")

    write(ROOT / "bottom_sheet_attachment_picker.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/attachmentSheetRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="@dimen/space_md">
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="@string/attachment_picker_title" android:textAppearance="?attr/textAppearanceTitleMedium"/>
    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
        <com.google.android.material.button.MaterialButton android:id="@+id/pickImage" style="@style/Widget.Material3.Button.OutlinedButton" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:drawableTop="@drawable/ic_image" android:minHeight="@dimen/min_touch_target" android:text="@string/attachment_pick_image"/>
        <com.google.android.material.button.MaterialButton android:id="@+id/pickFile" style="@style/Widget.Material3.Button.OutlinedButton" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:drawableTop="@drawable/ic_description" android:minHeight="@dimen/min_touch_target" android:text="@string/attachment_pick_file"/>
    </LinearLayout>
</LinearLayout>
""")

    write(ROOT / "include_reaction_picker.xml", """
<?xml version="1.0" encoding="utf-8"?>
<HorizontalScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/reactionPicker"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/colorSurfaceContainerHigh"
    android:padding="@dimen/space_sm"
    android:visibility="gone">
    <LinearLayout android:id="@+id/reactionEmojiRow" android:layout_width="wrap_content" android:layout_height="wrap_content" android:orientation="horizontal"/>
</HorizontalScrollView>
""")

    write(ROOT / "view_empty_state.xml", """
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android">
    <ImageView android:id="@+id/emptyIcon" android:layout_width="72dp" android:layout_height="72dp" android:layout_gravity="center_horizontal" android:importantForAccessibility="no"/>
    <TextView android:id="@+id/emptyMessage" android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center" android:textAppearance="?attr/textAppearanceBodyLarge"/>
    <com.google.android.material.button.MaterialButton android:id="@+id/emptyAction" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_gravity="center_horizontal" android:minHeight="@dimen/min_touch_target" android:visibility="gone"/>
</merge>
""")

    write(ROOT / "view_error_state.xml", """
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android">
    <ImageView android:id="@+id/errorIcon" android:layout_width="48dp" android:layout_height="48dp" android:importantForAccessibility="no" android:src="@drawable/ic_warning"/>
    <TextView android:id="@+id/errorMessage" android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center" android:textAppearance="?attr/textAppearanceBodyLarge" android:textColor="?attr/colorError"/>
    <com.google.android.material.button.MaterialButton android:id="@+id/errorRetry" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_gravity="center_horizontal" android:minHeight="@dimen/min_touch_target" android:text="@string/action_retry" android:visibility="gone"/>
</merge>
""")

    write(ROOT / "view_loading_state.xml", """
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android">
    <com.google.android.material.progressindicator.CircularProgressIndicator android:id="@+id/loadingSpinner" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_gravity="center" android:indeterminate="true"/>
    <TextView android:id="@+id/loadingLabel" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_gravity="center_horizontal" android:textAppearance="?attr/textAppearanceBodyMedium" android:visibility="gone"/>
</merge>
""")

    write(ROOT / "view_section_header.xml", """
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android">
    <TextView android:id="@+id/sectionTitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:paddingHorizontal="@dimen/space_md" android:paddingVertical="@dimen/space_sm" android:textAppearance="?attr/textAppearanceLabelMedium" android:textColor="?attr/colorPrimary"/>
</merge>
""")

    write(ROOT / "view_settings_row.xml", """
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android">
    <ImageView android:id="@+id/rowIcon" android:layout_width="36dp" android:layout_height="36dp" android:importantForAccessibility="no"/>
    <TextView android:id="@+id/rowTitle" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:textAppearance="?attr/textAppearanceTitleMedium"/>
    <TextView android:id="@+id/rowSubtitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:textAppearance="?attr/textAppearanceBodySmall" android:visibility="gone"/>
    <TextView android:id="@+id/rowValue" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textAppearance="?attr/textAppearanceBodyMedium" android:visibility="gone"/>
    <ImageView android:id="@+id/rowChevron" android:layout_width="24dp" android:layout_height="24dp" android:importantForAccessibility="no" android:src="@drawable/ic_keyboard_arrow_right"/>
</merge>
""")


if __name__ == "__main__":
    main()
