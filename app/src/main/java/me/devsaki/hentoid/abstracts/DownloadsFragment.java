package me.devsaki.hentoid.abstracts;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.design.widget.Snackbar;
import android.support.v4.app.FragmentActivity;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.annimon.stream.Stream;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import me.devsaki.hentoid.HentoidApp;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.activities.ImportActivity;
import me.devsaki.hentoid.activities.SearchActivity;
import me.devsaki.hentoid.activities.bundles.SearchActivityBundle;
import me.devsaki.hentoid.adapters.ContentAdapter;
import me.devsaki.hentoid.collection.CollectionAccessor;
import me.devsaki.hentoid.collection.mikan.MikanCollectionAccessor;
import me.devsaki.hentoid.database.ObjectBoxCollectionAccessor;
import me.devsaki.hentoid.database.ObjectBoxDB;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.enums.Language;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.events.DownloadEvent;
import me.devsaki.hentoid.events.ImportEvent;
import me.devsaki.hentoid.fragments.AboutMikanDialogFragment;
import me.devsaki.hentoid.fragments.SearchBookIdDialogFragment;
import me.devsaki.hentoid.listener.ContentListener;
import me.devsaki.hentoid.listener.ItemClickListener.ItemSelectListener;
import me.devsaki.hentoid.services.ContentQueueManager;
import me.devsaki.hentoid.util.ConstsImport;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.PermissionUtil;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.RandomSeedSingleton;
import me.devsaki.hentoid.util.ToastUtil;
import timber.log.Timber;

import static com.annimon.stream.Collectors.toCollection;

/**
 * Created by avluis on 08/27/2016. Common elements for use by EndlessFragment and PagerFragment
 * <p>
 * todo issue: After requesting for permission, the app is reset using {@link #resetApp()} instead
 * of implementing {@link #onRequestPermissionsResult(int, String[], int[])} to receive permission
 * request result
 */
public abstract class DownloadsFragment extends BaseFragment implements ContentListener,
        ItemSelectListener {

    // ======== CONSTANTS

    protected static final int SHOW_LOADING = 1;
    protected static final int SHOW_BLANK = 2;
    protected static final int SHOW_RESULT = 3;

    public final static int MODE_LIBRARY = 0;
    public final static int MODE_MIKAN = 1;


    // Save state constants

    private static final String SELECTED_TAGS = "selected_tags";
    private static final String FILTER_FAVOURITES = "filter_favs";
    private static final String CURRENT_PAGE = "current_page";
    private static final String QUERY = "query";
    private static final String MODE = "mode";


    // ======== UI ELEMENTS

    // Top tooltip appearing when a download has been completed
    protected LinearLayout newContentToolTip;
    // "Search" button on top menu
    private MenuItem searchMenu;
    // "Toggle favourites" button on top menu
    private MenuItem favsMenu;
    // "Sort" button on top menu
    private MenuItem orderMenu;
    // Action view associated with search menu button
    private SearchView mainSearchView;
    // Search pane that shows up on top when using search function
    protected View advancedSearchPane;
    // Layout containing the list of books
    private SwipeRefreshLayout refreshLayout;
    // List containing all books
    protected RecyclerView mListView;
    // Layout manager associated with the above list view
    protected LinearLayoutManager llm;
    // Pane saying "Loading up~"
    private TextView loadingText;
    // Pane saying "Why am I empty ?"
    private TextView emptyText;
    // Bottom toolbar with page numbers
    protected LinearLayout pagerToolbar;
    // Bar with CLEAR button that appears whenever a search filter is active
    private ViewGroup filterBar;
    // Book count text on the filter bar
    private TextView filterBookCount;
    // CLEAR button on the filter bar
    private TextView filterClearButton;

    // ======== UTIL OBJECTS
    private ObjectAnimator animator;

    // ======== VARIABLES TAKEN FROM PREFERENCES / GLOBAL SETTINGS TO DETECT CHANGES
    // Books per page
    protected int booksPerPage;

    // ======== VARIABLES

    // === MISC. USAGE
    protected Context mContext;
    // Current page of collection view (NB : In EndlessFragment, a "page" is a group of loaded books. Last page is reached when scrolling reaches the very end of the book list)
    protected int currentPage = 1;
    // Adapter in charge of book list display
    protected ContentAdapter mAdapter;
    // True if a new download is ready; used to display / hide "New Content" tooltip when scrolling
    protected boolean isNewContentAvailable;
    // True if book list is being loaded; used for synchronization between threads
    protected boolean isLoading;
    // Indicates whether or not one of the books has been selected
    private boolean isSelected;
    // Records the system time (ms) when back button has been last pressed (to detect "double back button" event)
    private long backButtonPressed;
    // True if bottom toolbar visibility is fixed and should not change regardless of scrolling; false if bottom toolbar visibility changes according to scrolling
    protected boolean overrideBottomToolbarVisibility;
    // True if storage permissions have been checked at least once
    private boolean storagePermissionChecked = false;
    // Mode : show library or show Mikan search
    private int mode = MODE_LIBRARY;
    // Collection accessor (DB or external, depending on mode)
    private CollectionAccessor collectionAccessor;
    // Total count of book in entire selected/queried collection (Adapter is in charge of updating it)
    private long mTotalSelectedCount = -1; // -1 = uninitialized (no query done yet)
    // Total count of book in entire collection (Adapter is in charge of updating it)
    private long mTotalCount = -1; // -1 = uninitialized (no query done yet)
    // Used to ignore native calls to onQueryTextChange
    boolean invalidateNextQueryTextChange = false;
    // Used to detect if the library has been refreshed
    boolean libraryHasBeenRefreshed = false;
    // If library has been refreshed, indicated new content count
    int refreshedContentCount = 0;


    // === SEARCH
    // Favourite filter active
    private boolean filterFavourites = false;
    // Expression typed in the search bar
    protected String query = "";
    // Current search tags
    private List<Attribute> selectedSearchTags = new ArrayList<>();
    // Last search parameters; used to determine whether or not page number should be reset to 1
    private String lastSearchParams = "";


    // To be documented
    private ActionMode mActionMode;
    private boolean selectTrigger = false;


    private static int getIconFromSortOrder(int sortOrder) {
        switch (sortOrder) {
            case Preferences.Constant.ORDER_CONTENT_LAST_DL_DATE_FIRST:
                return R.drawable.ic_menu_sort_321;
            case Preferences.Constant.ORDER_CONTENT_LAST_DL_DATE_LAST:
                return R.drawable.ic_menu_sort_by_date;
            case Preferences.Constant.ORDER_CONTENT_TITLE_ALPHA:
                return R.drawable.ic_menu_sort_alpha;
            case Preferences.Constant.ORDER_CONTENT_TITLE_ALPHA_INVERTED:
                return R.drawable.ic_menu_sort_za;
            case Preferences.Constant.ORDER_CONTENT_LEAST_READ:
                return R.drawable.ic_menu_sort_unread;
            case Preferences.Constant.ORDER_CONTENT_MOST_READ:
                return R.drawable.ic_menu_sort_read;
            case Preferences.Constant.ORDER_CONTENT_LAST_READ:
                return R.drawable.ic_menu_sort_last_read;
            case Preferences.Constant.ORDER_CONTENT_RANDOM:
                return R.drawable.ic_menu_sort_random;
            default:
                return R.drawable.ic_error;
        }
    }


    // == METHODS

    // Called when the action mode is created; startActionMode() was called.
    private final ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
        // Called when action mode is first created.
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Inflate a menu resource providing context menu items
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.menu_context_menu, menu);

            return true;
        }

        // Called each time the action mode is shown. Always called after onCreateActionMode,
        // but may be called multiple times if the mode is invalidated.
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            menu.findItem(R.id.action_delete).setVisible(!selectTrigger);
            menu.findItem(R.id.action_share).setVisible(!selectTrigger);
            menu.findItem(R.id.action_archive).setVisible(!selectTrigger);
            menu.findItem(R.id.action_delete_sweep).setVisible(selectTrigger);

            return true;
        }

        // Called when the user selects a contextual menu item.
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.action_share:
                    mAdapter.sharedSelectedItems();
                    mode.finish();

                    return true;
                case R.id.action_delete:
                    mAdapter.purgeSelectedItems();
                    mode.finish();

                    return true;
                case R.id.action_archive:
                    mAdapter.archiveSelectedItems();
                    mode.finish();

                    return true;
                case R.id.action_delete_sweep:
                    mAdapter.purgeSelectedItems();
                    mode.finish();

                    return true;
                default:
                    return false;
            }
        }

        // Called when the user exits the action mode.
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            clearSelection();
            mActionMode = null;
        }
    };

    @Override
    public void onResume() {
        super.onResume();

        defaultLoad();
    }

    /**
     * Check write permissions on target storage and load library
     */
    private void defaultLoad() {

        if (MODE_LIBRARY == mode) {
            if (PermissionUtil.requestExternalStoragePermission(requireActivity(), ConstsImport.RQST_STORAGE_PERMISSION)) {
                boolean shouldUpdate = queryPrefs();
                if (shouldUpdate || -1 == mTotalSelectedCount)
                    searchLibrary(true); // If prefs changes detected or first run (-1 = uninitialized)
                if (ContentQueueManager.getInstance().getDownloadCount() > 0) showReloadToolTip();
                showToolbar(true);
            } else {
                Timber.d("Storage permission denied!");
                if (storagePermissionChecked) {
                    resetApp();
                }
                storagePermissionChecked = true;
            }
        } else if (MODE_MIKAN == mode) {
            if (-1 == mTotalSelectedCount) searchLibrary(true);
            showToolbar(true);
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onImportEvent(ImportEvent event) {
        if (ImportEvent.EV_COMPLETE == event.eventType) {
            EventBus.getDefault().removeStickyEvent(event);
            libraryHasBeenRefreshed = true;
            refreshedContentCount = event.booksOK;
        }
    }

    /**
     * Updates class variables with Hentoid user preferences
     */
    protected boolean queryPrefs() {
        Timber.d("Querying Prefs.");
        boolean shouldUpdate = false;

        if (Preferences.getRootFolderName().isEmpty()) {
            Timber.d("Where are my files?!");

            FragmentActivity activity = requireActivity();
            Intent intent = new Intent(activity, ImportActivity.class);
            startActivity(intent);
            activity.finish();
        }

        if (libraryHasBeenRefreshed && mTotalCount > -1) {
            Timber.d("Library has been refreshed !  %s -> %s books", mTotalCount, refreshedContentCount);

            if (refreshedContentCount > mTotalCount) { // More books added
                showReloadToolTip();
            } else { // Library cleaned up
                shouldUpdate = true;
            }
            libraryHasBeenRefreshed = false;
            refreshedContentCount = 0;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            checkStorage();
        }

        int booksPerPage = Preferences.getContentPageQuantity();

        if (this.booksPerPage != booksPerPage) {
            Timber.d("booksPerPage updated.");
            this.booksPerPage = booksPerPage;
            setQuery("");
            shouldUpdate = true;
        }

        return shouldUpdate;
    }

    private void checkStorage() {
        if (FileHelper.isSAF()) {
            File storage = new File(Preferences.getRootFolderName());
            if (FileHelper.getExtSdCardFolder(storage) == null) {
                Timber.d("Where are my files?!");
                ToastUtil.toast(requireActivity(),
                        "Could not find library!\nPlease check your storage device.", Toast.LENGTH_LONG);
                setQuery("      ");

                new Handler().postDelayed(() -> {
                    FragmentActivity activity = requireActivity();
                    activity.finish();
                    Runtime.getRuntime().exit(0);
                }, 3000);
            }
            checkSDHealth();
        }
    }

    private void checkSDHealth() {
        if (!FileHelper.isWritable(new File(Preferences.getRootFolderName()))) {
            ToastUtil.toast(R.string.sd_access_error);
            new AlertDialog.Builder(requireActivity())
                    .setMessage(R.string.sd_access_fatal_error)
                    .setTitle("Error!")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    /**
     * Reset the app (to get write permissions)
     */
    private void resetApp() {
        Helper.reset(HentoidApp.getAppContext(), requireActivity());
    }

    @Override
    public void onPause() {
        super.onPause();

        clearSelection();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(FILTER_FAVOURITES, filterFavourites);
        outState.putString(QUERY, query);
        outState.putInt(CURRENT_PAGE, currentPage);
        outState.putInt(MODE, mode);

        long[] selectedTagIds = new long[selectedSearchTags.size()];
        int index = 0;
        for (Attribute a : selectedSearchTags) {
            selectedTagIds[index++] = a.getId();
        }
        outState.putLongArray(SELECTED_TAGS, selectedTagIds);
//        outState.putIntegerArrayList(SELECTED_TAGS, selectedTagIds);
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle state) {
        super.onViewStateRestored(state);

        if (state != null) {
            filterFavourites = state.getBoolean(FILTER_FAVOURITES, false);
            query = state.getString(QUERY, "");
            currentPage = state.getInt(CURRENT_PAGE);
            mode = state.getInt(MODE);

            long[] selectedTagIds = state.getLongArray(SELECTED_TAGS);
//            List<Integer> selectedTagIds = state.getIntegerArrayList(SELECTED_TAGS);
            ObjectBoxDB db = ObjectBoxDB.getInstance(requireContext());
            if (selectedTagIds != null) {
                for (long i : selectedTagIds) {
                    Attribute a = db.selectAttributeById(i);
                    if (a != null) {
                        selectedSearchTags.add(a);
                    }
                }
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
        setHasOptionsMenu(true);

        mContext = getContext();
        booksPerPage = Preferences.getContentPageQuantity();
    }

    @Override
    public void onDestroy() {
        collectionAccessor.dispose();
        mAdapter.dispose();
        super.onDestroy();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, final ViewGroup container,
                             Bundle savedInstanceState) {
        if (this.getArguments() != null) mode = this.getArguments().getInt("mode");
        collectionAccessor = (MODE_LIBRARY == mode) ? new ObjectBoxCollectionAccessor(mContext) : new MikanCollectionAccessor(mContext);

        View rootView = inflater.inflate(R.layout.fragment_downloads, container, false);

        initUI(rootView);
        attachScrollListener();
        attachOnClickListeners(rootView);

        return rootView;
    }

    protected void initUI(View rootView) {
        loadingText = rootView.findViewById(R.id.loading);
        emptyText = rootView.findViewById(R.id.empty);
        emptyText.setText((MODE_LIBRARY == mode) ? R.string.downloads_empty_library : R.string.downloads_empty_mikan);

        int contentSortOrder = Preferences.getContentSortOrder();

        if (MODE_MIKAN == mode)
            contentSortOrder = Preferences.Constant.ORDER_CONTENT_LAST_UL_DATE_FIRST;

        llm = new LinearLayoutManager(mContext);

        mAdapter = new ContentAdapter.Builder()
                .setContext(mContext)
                .setCollectionAccessor(collectionAccessor)
                .setDisplayMode(mode)
                .setSortComparator(Content.getComparator(contentSortOrder))
                .setItemSelectListener(this)
                .setOnContentRemovedListener(this::onContentRemoved)
                .build();

        // Main view
        mListView = rootView.findViewById(R.id.list);
        mListView.setHasFixedSize(true);
        mListView.setLayoutManager(llm);
        mListView.setAdapter(mAdapter);
        mListView.setVisibility(View.GONE);

        loadingText.setVisibility(View.VISIBLE);


        pagerToolbar = rootView.findViewById(R.id.downloads_toolbar);
        newContentToolTip = rootView.findViewById(R.id.tooltip);
        refreshLayout = rootView.findViewById(R.id.swipe_container);

        filterBar = rootView.findViewById(R.id.filter_bar);
        filterBookCount = rootView.findViewById(R.id.filter_book_count);
        filterClearButton = rootView.findViewById(R.id.filter_clear);

        advancedSearchPane = rootView.findViewById(R.id.advanced_search);
        advancedSearchPane.setOnClickListener(v -> onAdvancedSearchClick());
    }

    protected void attachScrollListener() {
        mListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                // Show toolbar:
                if (!overrideBottomToolbarVisibility && mAdapter.getItemCount() > 0) {
                    // At top of list
                    int firstVisibleItemPos = llm.findFirstVisibleItemPosition();
                    View topView = llm.findViewByPosition(firstVisibleItemPos);
                    if (topView != null && topView.getTop() == 0 && firstVisibleItemPos == 0) {
                        showToolbar(true);
                        if (isNewContentAvailable) {
                            newContentToolTip.setVisibility(View.VISIBLE);
                        }
                    }

                    // Last item in list
                    if (llm.findLastVisibleItemPosition() == mAdapter.getItemCount() - 1) {
                        showToolbar(true);
                        if (isNewContentAvailable) {
                            newContentToolTip.setVisibility(View.VISIBLE);
                        }
                    } else {
                        // When scrolling up
                        if (dy < -10) {
                            showToolbar(true);
                            if (isNewContentAvailable) {
                                newContentToolTip.setVisibility(View.VISIBLE);
                            }
                            // When scrolling down
                        } else if (dy > 100) {
                            showToolbar(false);
                            if (isNewContentAvailable) {
                                newContentToolTip.setVisibility(View.GONE);
                            }
                        }
                    }
                }
            }
        });
    }

    protected void attachOnClickListeners(View rootView) {
        newContentToolTip.setOnClickListener(v -> commitRefresh());

        filterClearButton.setOnClickListener(v -> {
            setQuery("");
            selectedSearchTags.clear();
            filterBar.setVisibility(View.GONE);
            searchLibrary(true);
        });

        refreshLayout.setEnabled(false);
        refreshLayout.setOnRefreshListener(this::commitRefresh);
    }

    @Override
    public boolean onBackPressed() {
        // If content is selected, deselect it
        if (isSelected) {
            clearSelection();
            backButtonPressed = 0;

            return false;
        }

        // If none of the above, user is asking to leave => use double-tap
        if (MODE_MIKAN == mode || backButtonPressed + 2000 > System.currentTimeMillis()) {
            return true;
        } else {
            backButtonPressed = System.currentTimeMillis();
            ToastUtil.toast(mContext, R.string.press_back_again);

            if (llm != null) {
                llm.scrollToPositionWithOffset(0, 0);
            }
        }

        return false;
    }


    /**
     * Clear search query and hide the search view if asked so
     */
    protected void clearQuery() {
        setQuery(query = "");
        searchLibrary(true);
    }

    /**
     * Refresh the whole screen - Called by pressing the "New Content" button that appear on new
     * downloads - Called by scrolling up when being on top of the list ("force reload" command)
     */
    protected void commitRefresh() {
        newContentToolTip.setVisibility(View.GONE);
        refreshLayout.setRefreshing(false);
        refreshLayout.setEnabled(false);
        isNewContentAvailable = false;
        searchLibrary(true);
        resetCount();
    }

    /**
     * Reset the download count (used to properly display the number of downloads in Notifications)
     */
    private void resetCount() {
        Timber.d("Download Count: %s", ContentQueueManager.getInstance().getDownloadCount());
        ContentQueueManager.getInstance().setDownloadCount(0);

        NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(0);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        if (event.eventType == DownloadEvent.EV_COMPLETE && !isLoading) {
            if (MODE_LIBRARY == mode) showReloadToolTip();
            else mAdapter.switchStateToDownloaded(event.content);
        }
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_content_list, menu);

        MenuItem aboutMikanMenu = menu.findItem(R.id.action_about_mikan);
        aboutMikanMenu.setVisible(MODE_MIKAN == mode);
        if (MODE_MIKAN == mode) {
            aboutMikanMenu.setOnMenuItemClickListener(item -> {
                AboutMikanDialogFragment.show(getFragmentManager());
                return true;
            });
        }

        orderMenu = menu.findItem(R.id.action_order);
        orderMenu.setVisible(MODE_LIBRARY == mode);

        searchMenu = menu.findItem(R.id.action_search);
        searchMenu.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                setSearchPaneVisibility(true);

                // Re-sets the query on screen, since default behaviour removes it right after collapse _and_ expand
                if (query != null && !query.isEmpty())
                    // Use of handler allows to set the value _after_ the UI has auto-cleared it
                    // Without that handler the view displays with an empty value
                    new Handler().postDelayed(() -> {
                        invalidateNextQueryTextChange = true;
                        mainSearchView.setQuery(query, false);
                    }, 100);

                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                setSearchPaneVisibility(false);
                return true;
            }
        });

        favsMenu = menu.findItem(R.id.action_favourites);
        favsMenu.setVisible(MODE_LIBRARY == mode);
        updateFavouriteFilter();
        favsMenu.setOnMenuItemClickListener(item -> {
            toggleFavouriteFilter();
            return true;
        });

        mainSearchView = (SearchView) searchMenu.getActionView();
        mainSearchView.setIconifiedByDefault(true);
        mainSearchView.setQueryHint(getString(R.string.search_hint));
        // Change display when text query is typed
        mainSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String s) {
                submitContentSearchQuery(s);
                mainSearchView.clearFocus();

                return true;
            }

            @Override
            public boolean onQueryTextChange(String s) {
                if (invalidateNextQueryTextChange) { // Should not happen when search panel is closing or opening
                    invalidateNextQueryTextChange = false;
                    return true;
                }

//                    if (!s.equals(query)) submitContentSearchQuery(s, 2000);  Auto-submit disabled
                if (s.isEmpty()) {
                    clearQuery();
                }

                return true;
            }
        });

        // Sets the starting book sort icon according to the current sort order
        orderMenu.setIcon(getIconFromSortOrder(Preferences.getContentSortOrder()));
    }

    private void onAdvancedSearchClick() {
        Intent search = new Intent(this.getContext(), SearchActivity.class);

        SearchActivityBundle.Builder builder = new SearchActivityBundle.Builder();

        builder.setMode(mode);
        if (!selectedSearchTags.isEmpty())
            builder.setUri(Helper.buildSearchUri(selectedSearchTags));
        search.putExtras(builder.getBundle());

        startActivityForResult(search, 999);
        searchMenu.collapseActionView();
    }

    /**
     * Callback method used when a sort method is selected in the sort drop-down menu => Updates the
     * UI according to the chosen sort method
     *
     * @param item MenuItem that has been selected
     * @return true if the order has been successfully processed
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int contentSortOrder;

        switch (item.getItemId()) {
            case R.id.action_order_AZ:
                contentSortOrder = Preferences.Constant.ORDER_CONTENT_TITLE_ALPHA;
                break;
            case R.id.action_order_321:
                contentSortOrder = Preferences.Constant.ORDER_CONTENT_LAST_DL_DATE_FIRST;
                break;
            case R.id.action_order_ZA:
                contentSortOrder = Preferences.Constant.ORDER_CONTENT_TITLE_ALPHA_INVERTED;
                break;
            case R.id.action_order_123:
                contentSortOrder = Preferences.Constant.ORDER_CONTENT_LAST_DL_DATE_LAST;
                break;
            case R.id.action_order_least_read:
                contentSortOrder = Preferences.Constant.ORDER_CONTENT_LEAST_READ;
                break;
            case R.id.action_order_most_read:
                contentSortOrder = Preferences.Constant.ORDER_CONTENT_MOST_READ;
                break;
            case R.id.action_order_last_read:
                contentSortOrder = Preferences.Constant.ORDER_CONTENT_LAST_READ;
                break;
            case R.id.action_order_random:
                contentSortOrder = Preferences.Constant.ORDER_CONTENT_RANDOM;
                RandomSeedSingleton.getInstance().renewSeed();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }

        mAdapter.setSortComparator(Content.getComparator(contentSortOrder));
        orderMenu.setIcon(getIconFromSortOrder(contentSortOrder));
        Preferences.setContentSortOrder(contentSortOrder);
        searchLibrary(true);

        return true;
    }

    /**
     * Toggles the visibility of the search pane
     *
     * @param visible True if search pane has to become visible; false if not
     */
    private void setSearchPaneVisibility(boolean visible) {
        advancedSearchPane.setVisibility(visible ? View.VISIBLE : View.GONE);
        invalidateNextQueryTextChange = true;
    }

    /**
     * Toggles favourite filter on a book and updates the UI accordingly
     */
    private void toggleFavouriteFilter() {
        filterFavourites = !filterFavourites;
        updateFavouriteFilter();
        searchLibrary(true);
    }

    /**
     * Update favourite filter button appearance (icon and color) on a book
     */
    private void updateFavouriteFilter() {
        favsMenu.setIcon(filterFavourites ? R.drawable.ic_fav_full : R.drawable.ic_fav_empty);
    }

    private void submitContentSearchQuery(final String s) {
        query = s;
        selectedSearchTags.clear(); // If user searches in main toolbar, universal search takes over advanced search
        setQuery(s);
        searchLibrary(true);
    }

    private void showReloadToolTip() {
        if (newContentToolTip.getVisibility() == View.GONE) {
            newContentToolTip.setVisibility(View.VISIBLE);
            refreshLayout.setEnabled(true);
            isNewContentAvailable = true;
        }
    }

    private void setQuery(String query) {
        this.query = query;
        currentPage = 1;
    }

    /**
     * Returns the current value of the query typed in the search toolbar; empty string if no query
     * typed
     *
     * @return Current value of the query typed in the search toolbar; empty string if no query
     * typed
     */
    private String getQuery() {
        return query == null ? "" : query;
    }

    private void clearSelection() {
        if (mAdapter != null) {
            if (mListView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                mAdapter.clearSelections();
                selectTrigger = false;
                showToolbar(true);
            }
            isSelected = false;
        }
    }

    protected void toggleUI(int mode) {
        switch (mode) {
            case SHOW_LOADING:
                mListView.setVisibility(View.GONE);
                emptyText.setVisibility(View.GONE);
                loadingText.setVisibility(View.VISIBLE);
                //showToolbar(false);
                startLoadingTextAnimation();
                break;
            case SHOW_BLANK:
                mListView.setVisibility(View.GONE);
                emptyText.setVisibility(View.VISIBLE);
                loadingText.setVisibility(View.GONE);
                showToolbar(false);
                break;
            case SHOW_RESULT:
                mListView.setVisibility(View.VISIBLE);
                emptyText.setVisibility(View.GONE);
                loadingText.setVisibility(View.GONE);
                showToolbar(true);
                break;
            default:
                stopLoadingTextAnimation();
                loadingText.setVisibility(View.GONE);
                break;
        }
    }

    private void startLoadingTextAnimation() {
        final int POWER_LEVEL = 9000;

        Drawable[] compoundDrawables = loadingText.getCompoundDrawables();
        for (Drawable drawable : compoundDrawables) {
            if (drawable == null) {
                continue;
            }

            animator = ObjectAnimator.ofInt(drawable, "level", 0, POWER_LEVEL);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.start();
        }
    }

    private void stopLoadingTextAnimation() {
        Drawable[] compoundDrawables = loadingText.getCompoundDrawables();
        for (Drawable drawable : compoundDrawables) {
            if (drawable == null) {
                continue;
            }
            animator.cancel();
        }
    }


    /**
     * Indicates whether a search query is active (using universal search or advanced search) or not
     *
     * @return True if a search query is is active (using universal search or advanced search); false if not (=whole unfiltered library selected)
     */
    private boolean isSearchQueryActive() {
        return (getQuery().length() > 0 || selectedSearchTags.size() > 0);
    }

    /**
     * Create a "thumbprint" unique to the combination of current search parameters
     *
     * @return Search parameters thumbprint
     */
    private String getCurrentSearchParams(int contentSortOrder) {
        StringBuilder result = new StringBuilder(mode == MODE_LIBRARY ? "L" : "M");
        result.append(".").append(query);
        for (Attribute a : selectedSearchTags) result.append(".").append(a.getName());
        result.append(".").append(booksPerPage);
        result.append(".").append(contentSortOrder);
        result.append(".").append(filterFavourites);

        return result.toString();
    }

    /**
     * Loads the library applying current search parameters
     *
     * @param showLoadingPanel True if loading panel has to appear while search is running
     */
    protected void searchLibrary(boolean showLoadingPanel) {
        isLoading = true;
        int contentSortOrder = Preferences.getContentSortOrder();

        if (showLoadingPanel) toggleUI(SHOW_LOADING);

        // New searches always start from page 1
        String currentSearchParams = getCurrentSearchParams(contentSortOrder);
        if (!currentSearchParams.equals(lastSearchParams)) {
            currentPage = 1;
            mListView.scrollToPosition(0);
        }
        lastSearchParams = currentSearchParams;

        if (!getQuery().isEmpty())
            collectionAccessor.searchBooksUniversal(getQuery(), currentPage, booksPerPage, contentSortOrder, filterFavourites, this); // Universal search
        else if (!selectedSearchTags.isEmpty())
            collectionAccessor.searchBooks("", selectedSearchTags, currentPage, booksPerPage, contentSortOrder, filterFavourites, this); // Advanced search
        else
            collectionAccessor.getRecentBooks(Site.HITOMI, Language.ANY, currentPage, booksPerPage, contentSortOrder, filterFavourites, this); // Default search (display recent)
    }

    protected abstract void showToolbar(boolean show);

    protected abstract void displayResults(List<Content> results, long totalSelectedContent);

    /**
     * Indicates if current page is the last page of the library
     *
     * @return true if last page has been reached
     */
    protected boolean isLastPage() {
        return (currentPage * booksPerPage >= mTotalSelectedCount);
    }

    private void displayNoResults() {
        if (!query.isEmpty()) {
            emptyText.setText(R.string.search_entry_not_found);
        } else {
            emptyText.setText((MODE_LIBRARY == mode) ? R.string.downloads_empty_library : R.string.downloads_empty_mikan);
        }
        toggleUI(SHOW_BLANK);
    }

    /**
     * Update the screen title according to current search filter (#TOTAL BOOKS) if no filter is
     * enabled (#FILTERED / #TOTAL BOOKS) if a filter is enabled
     */
    private void updateTitle() {
        if (MODE_LIBRARY == mode) {
            Activity activity = getActivity();
            if (activity != null) { // Has to be crash-proof; sometimes there's no activity there...
                String title;
                if (mTotalSelectedCount == mTotalCount)
                    title = "(" + mTotalCount + ")";
                else title = "(" + mTotalSelectedCount + "/" + mTotalCount + ")";
                activity.setTitle(title);
            }
        }
    }

    /*
    ContentListener implementation
     */
    @Override
    public void onContentReady(List<Content> results, long totalSelectedContent, long totalContent) {
        Timber.d("Content results have loaded : %s results; %s total selected count, %s total count", results.size(), totalSelectedContent, totalContent);
        isLoading = false;

        if (isSearchQueryActive()) {
            // Disable "New content" popup
            if (isNewContentAvailable) {
                newContentToolTip.setVisibility(View.GONE);
                isNewContentAvailable = false;
            }

            @StringRes int textRes = totalSelectedContent > 1 ?
                    R.string.downloads_filter_book_count_plural :
                    R.string.downloads_filter_book_count;

            filterBookCount.setText(getString(textRes, totalSelectedContent));
            filterBar.setVisibility(View.VISIBLE);
            if (totalSelectedContent > 0 && searchMenu != null) searchMenu.collapseActionView();
        } else {
            filterBar.setVisibility(View.GONE);
        }

        // User searches a book ID
        if (Helper.isNumeric(query)) {
            ArrayList<Integer> siteCodes = Stream.of(results)
                    .map(Content::getSite)
                    .map(Site::getCode)
                    .collect(toCollection(ArrayList::new));

            SearchBookIdDialogFragment.invoke(requireFragmentManager(), query, siteCodes);
        }

        if (0 == totalSelectedContent) {
            displayNoResults();
        } else {
            displayResults(results, totalSelectedContent);
        }

        mTotalSelectedCount = totalSelectedContent;
        mTotalCount = totalContent;

        updateTitle();
    }

    @Override
    public void onContentFailed(Content content, String message) {
        Timber.w(message);
        isLoading = false;

        Snackbar.make(mListView, message, Snackbar.LENGTH_LONG)
                .setAction("RETRY", v -> searchLibrary(MODE_MIKAN == mode))
                .show();
        toggleUI(SHOW_BLANK);
    }

    /*
    ItemSelectListener implementation
     */
    @Override
    public void onItemSelected(int selectedCount) {
        isSelected = true;
        showToolbar(false);
        overrideBottomToolbarVisibility = true;

        if (selectedCount == 1) {
            mAdapter.notifyDataSetChanged();
        }

        if (selectedCount >= 2) {
            selectTrigger = true;
        }

        if (mActionMode == null) {
            mActionMode = pagerToolbar.startActionMode(mActionModeCallback);
        }

        if (mActionMode != null) {
            mActionMode.invalidate();
            mActionMode.setTitle(
                    selectedCount + (selectedCount > 1 ? " items selected" : " item selected"));
        }
    }

    @Override
    public void onItemClear(int itemCount) {
        if (mActionMode != null) {
            if (itemCount >= 1) {
                mActionMode.setTitle(
                        itemCount + (itemCount > 1 ? " items selected" : " item selected"));
            } else {
                selectTrigger = false;
                mActionMode.invalidate();
                mActionMode.setTitle("");
            }
        }

        if (itemCount == 1 && selectTrigger) {
            selectTrigger = false;

            if (mActionMode != null) {
                mActionMode.invalidate();
            }
        }

        if (itemCount < 1) {
            clearSelection();
            showToolbar(true);
            overrideBottomToolbarVisibility = false;

            if (mActionMode != null) {
                mActionMode.finish();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 999) {
            if (resultCode == Activity.RESULT_OK) {
                if (data != null && data.getExtras() != null) {
                    Uri searchUri = new SearchActivityBundle.Parser(data.getExtras()).getUri();

                    if (searchUri != null) {
                        setQuery(searchUri.getPath());
                        selectedSearchTags = Helper.parseSearchUri(searchUri);

                        searchLibrary(true);
                    }
                }
            }
        }
    }

    /**
     * Triggers when one or more items have been removed from the list
     *
     * @param i Number of items removed from the list
     */
    private void onContentRemoved(int i) {
        mTotalSelectedCount = mTotalSelectedCount - i;
        mTotalCount = mTotalCount - i;

        if (0 == mTotalCount) currentPage = 1;

        if (0 == mTotalSelectedCount) {
            displayNoResults();
            clearSelection();
        }

        updateTitle();
    }
}
