package awais.instagrabber;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.shape.MaterialShapeDrawable;

import java.util.Arrays;

import awais.instagrabber.activities.FollowViewer;
import awais.instagrabber.activities.Main;
import awais.instagrabber.activities.PostViewer;
import awais.instagrabber.activities.StoryViewer;
import awais.instagrabber.adapters.DiscoverAdapter;
import awais.instagrabber.adapters.FeedAdapter;
import awais.instagrabber.adapters.FeedStoriesAdapter;
import awais.instagrabber.adapters.PostsAdapter;
import awais.instagrabber.asyncs.DiscoverFetcher;
import awais.instagrabber.asyncs.FeedFetcher;
import awais.instagrabber.asyncs.FeedStoriesFetcher;
import awais.instagrabber.asyncs.HighlightsFetcher;
import awais.instagrabber.asyncs.PostsFetcher;
import awais.instagrabber.asyncs.ProfileFetcher;
import awais.instagrabber.asyncs.StoryStatusFetcher;
import awais.instagrabber.customviews.MouseDrawer;
import awais.instagrabber.customviews.RamboTextView;
import awais.instagrabber.customviews.helpers.GridAutofitLayoutManager;
import awais.instagrabber.customviews.helpers.GridSpacingItemDecoration;
import awais.instagrabber.customviews.helpers.RecyclerLazyLoader;
import awais.instagrabber.customviews.helpers.VideoAwareRecyclerScroller;
import awais.instagrabber.interfaces.FetchListener;
import awais.instagrabber.interfaces.MentionClickListener;
import awais.instagrabber.models.BasePostModel;
import awais.instagrabber.models.DiscoverItemModel;
import awais.instagrabber.models.FeedModel;
import awais.instagrabber.models.FeedStoryModel;
import awais.instagrabber.models.IntentModel;
import awais.instagrabber.models.PostModel;
import awais.instagrabber.models.StoryModel;
import awais.instagrabber.models.enums.IntentModelType;
import awais.instagrabber.models.enums.ItemGetType;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.Utils;
import awaisomereport.LogCollector;

import static awais.instagrabber.utils.Constants.AUTOLOAD_POSTS;
import static awais.instagrabber.utils.Constants.BOTTOM_TOOLBAR;
import static awais.instagrabber.utils.Utils.logCollector;

public final class MainHelper implements SwipeRefreshLayout.OnRefreshListener {
    private static AsyncTask<?, ?, ?> currentlyExecuting;
    private AsyncTask<Void, Void, FeedStoryModel[]> prevStoriesFetcher;
    private final boolean autoloadPosts;
    private boolean hasNextPage = false, feedHasNextPage = false, discoverHasMore = false;
    private String endCursor = null, feedEndCursor = null, discoverEndMaxId = null;
    private final FetchListener<PostModel[]> postsFetchListener = new FetchListener<PostModel[]>() {
        @Override
        public void onResult(final PostModel[] result) {
            if (result != null) {
                final int oldSize = main.allItems.size();
                main.allItems.addAll(Arrays.asList(result));

                postsAdapter.notifyItemRangeInserted(oldSize, result.length);

                main.mainBinding.mainPosts.post(() -> {
                    main.mainBinding.mainPosts.setNestedScrollingEnabled(true);
                    main.mainBinding.mainPosts.setVisibility(View.VISIBLE);
                });

                final String username;
                final String postFix;
                if (!isHashtag) {
                    username = main.profileModel.getUsername();
                    postFix = "/" + main.profileModel.getPostCount() + ')';
                } else {
                    username = null;
                    postFix = null;
                }

                if (isHashtag)
                    main.mainBinding.toolbar.toolbar.setTitle(main.getString(R.string.title_hashtag_prefix) + main.userQuery);
                else main.mainBinding.toolbar.toolbar.setTitle(username + " (" + main.allItems.size() + postFix);

                final PostModel model = result[result.length - 1];
                if (model != null) {
                    endCursor = model.getEndCursor();

                    if (endCursor == null && !isHashtag) {
                        main.mainBinding.toolbar.toolbar.setTitle(username + " (" + main.profileModel.getPostCount() + postFix);
                        final Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                main.mainBinding.toolbar.toolbar.setTitle(username);
                                handler.removeCallbacks(this);
                            }
                        }, 1000);
                    }

                    hasNextPage = model.hasNextPage();
                    if ((autoloadPosts && hasNextPage) && !isHashtag)
                        currentlyExecuting = new PostsFetcher(main.profileModel.getId(), endCursor, this)
                                .setUsername(main.profileModel.getUsername()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    else
                        main.mainBinding.swipeRefreshLayout.setRefreshing(false);
                    model.setPageCursor(false, null);
                }
            }
        }
    };
    private final FetchListener<FeedModel[]> feedFetchListener = new FetchListener<FeedModel[]>() {
        @Override
        public void doBefore() {
            main.mainBinding.feedSwipeRefreshLayout.post(() -> main.mainBinding.feedSwipeRefreshLayout.setRefreshing(true));
        }

        @Override
        public void onResult(final FeedModel[] result) {
            if (result != null) {
                final int oldSize = main.feedItems.size();
                main.feedItems.addAll(Arrays.asList(result));
                feedAdapter.notifyItemRangeInserted(oldSize, result.length);

                main.mainBinding.feedPosts.post(() -> main.mainBinding.feedPosts.setNestedScrollingEnabled(true));

                final PostModel feedPostModel = result[result.length - 1];
                if (feedPostModel != null) {
                    feedEndCursor = feedPostModel.getEndCursor();
                    feedHasNextPage = feedPostModel.hasNextPage();
                    feedPostModel.setPageCursor(false, null);
                }
            }

            main.mainBinding.feedSwipeRefreshLayout.setRefreshing(false);
        }
    };
    private final FetchListener<DiscoverItemModel[]> discoverFetchListener = new FetchListener<DiscoverItemModel[]>() {
        @Override
        public void doBefore() {
            main.mainBinding.discoverSwipeRefreshLayout.setRefreshing(true);
        }

        @Override
        public void onResult(final DiscoverItemModel[] result) {
            if (result != null) {
                final int oldSize = main.discoverItems.size();
                main.discoverItems.addAll(Arrays.asList(result));
                discoverAdapter.notifyItemRangeInserted(oldSize, result.length);

                final DiscoverItemModel discoverItemModel = result[result.length - 1];
                if (discoverItemModel != null) {
                    discoverEndMaxId = discoverItemModel.getNextMaxId();
                    discoverHasMore = discoverItemModel.hasMore();
                    discoverItemModel.setMore(false, null);
                }
            }

            main.mainBinding.discoverSwipeRefreshLayout.setRefreshing(false);
        }
    };
    private final FetchListener<FeedStoryModel[]> feedStoriesListener = new FetchListener<FeedStoryModel[]>() {
        @Override
        public void doBefore() {
            main.mainBinding.feedStories.setVisibility(View.GONE);
        }

        @Override
        public void onResult(final FeedStoryModel[] result) {
            feedStoriesAdapter.setData(result);
            if (result != null && result.length > 0)
                main.mainBinding.feedStories.setVisibility(View.VISIBLE);
        }
    };
    private final MentionClickListener mentionClickListener = new MentionClickListener() {
        @Override
        public void onClick(final RamboTextView view, final String text, final boolean isHashtag) {
            new AlertDialog.Builder(main).setMessage(isHashtag ? R.string.comment_view_mention_hash_search : R.string.comment_view_mention_user_search)
                    .setTitle(text).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ok, (dialog, which) -> {
                if (Main.scanHack != null) Main.scanHack.onResult(text);
            }).show();
        }
    };
    private final FeedStoriesAdapter feedStoriesAdapter = new FeedStoriesAdapter(null, new View.OnClickListener() {
        @Override
        public void onClick(final View v) {
            final Object tag = v.getTag();
            if (tag instanceof FeedStoryModel) {
                final FeedStoryModel feedStoryModel = (FeedStoryModel) tag;
                final StoryModel[] storyModels = feedStoryModel.getStoryModels();

                main.startActivity(new Intent(main, StoryViewer.class)
                        .putExtra(Constants.EXTRAS_STORIES, storyModels)
                        .putExtra(Constants.EXTRAS_USERNAME, feedStoryModel.getProfileModel().getUsername())
                );
            }
        }
    });
    @NonNull
    private final Main main;
    private final Resources resources;
    private final View collapsingToolbar;
    private final RecyclerLazyLoader lazyLoader;
    private boolean isHashtag;
    private PostsAdapter postsAdapter;
    private FeedAdapter feedAdapter;
    private RecyclerLazyLoader feedLazyLoader, discoverLazyLoader;
    private DiscoverAdapter discoverAdapter;
    public SimpleExoPlayer currentFeedPlayer; // hack for remix drawer layout

    public MainHelper(@NonNull final Main main) {
        stopCurrentExecutor();

        this.main = main;
        this.resources = main.getResources();
        this.autoloadPosts = Utils.settingsHelper.getBoolean(AUTOLOAD_POSTS);

        main.mainBinding.swipeRefreshLayout.setOnRefreshListener(this);
        main.mainBinding.mainUrl.setMovementMethod(new LinkMovementMethod());

        final boolean isLoggedIn = !Utils.isEmpty(Utils.settingsHelper.getString(Constants.COOKIE));

        final LinearLayout iconSlider = main.findViewById(R.id.iconSlider);
        final ImageView iconFeed = (ImageView) iconSlider.getChildAt(0);
        final ImageView iconProfile = (ImageView) iconSlider.getChildAt(1);
        final ImageView iconDiscover = (ImageView) iconSlider.getChildAt(2);

        final boolean isBottomToolbar = Utils.settingsHelper.getBoolean(BOTTOM_TOOLBAR);
        if (!isLoggedIn) {
            main.mainBinding.drawerLayout.removeView(main.mainBinding.feedLayout);
            main.mainBinding.drawerLayout.removeView(main.mainBinding.discoverSwipeRefreshLayout);
            iconFeed.setAlpha(0.4f);
            iconDiscover.setAlpha(0.4f);
        } else {
            iconFeed.setAlpha(1f);
            iconDiscover.setAlpha(1f);

            setupExplore();

            final boolean showFeed = Utils.settingsHelper.getBoolean(Constants.SHOW_FEED);
            if (showFeed) setupFeed();
            else {
                iconFeed.setAlpha(0.4f);
                main.mainBinding.drawerLayout.removeView(main.mainBinding.feedLayout);
            }

            final TypedValue resolvedAttr = new TypedValue();
            main.getTheme().resolveAttribute(android.R.attr.textColorPrimary, resolvedAttr, true);

            final int selectedItem = ContextCompat.getColor(main, resolvedAttr.resourceId != 0 ? resolvedAttr.resourceId : resolvedAttr.data);
            final ColorStateList colorStateList = ColorStateList.valueOf(selectedItem);

            main.mainBinding.toolbar.toolbar.measure(0, -1);
            final int toolbarMeasuredHeight = main.mainBinding.toolbar.toolbar.getMeasuredHeight();

            final ViewGroup.LayoutParams layoutParams = main.mainBinding.toolbar.toolbar.getLayoutParams();
            final MouseDrawer.DrawerListener simpleDrawerListener = new MouseDrawer.DrawerListener() {
                private final String titleDiscover = resources.getString(R.string.title_discover);

                @Override
                public void onDrawerSlide(final View drawerView, @MouseDrawer.EdgeGravity final int gravity, final float slideOffset) {
                    final int currentIconAlpha = (int) Math.max(100, 255 - 255 * slideOffset);
                    final int otherIconAlpha = (int) Math.max(100, 255 * slideOffset);

                    ImageViewCompat.setImageTintList(iconProfile, colorStateList.withAlpha(currentIconAlpha));

                    final boolean drawerOpening = slideOffset > 0.0f;
                    final int alpha;
                    final ColorStateList imageTintList;

                    if (gravity == GravityCompat.START) {
                        // this helps hide the toolbar when opening feed

                        final int roundedToolbarHeight;
                        final float toolbarHeight;

                        if (isBottomToolbar) {
                            toolbarHeight = toolbarMeasuredHeight * slideOffset;
                            roundedToolbarHeight = -Math.round(toolbarHeight);
                        } else {
                            toolbarHeight = -toolbarMeasuredHeight * slideOffset;
                            roundedToolbarHeight = Math.round(toolbarHeight);
                        }

                        layoutParams.height = Math.max(0, Math.min(toolbarMeasuredHeight, toolbarMeasuredHeight + roundedToolbarHeight));
                        main.mainBinding.toolbar.toolbar.setLayoutParams(layoutParams);
                        main.mainBinding.toolbar.toolbar.setTranslationY(toolbarHeight);

                        imageTintList = ImageViewCompat.getImageTintList(iconDiscover);
                        alpha = imageTintList != null ? (imageTintList.getDefaultColor() & 0xFF_000000) >> 24 : 0;

                        if (drawerOpening && alpha > 100)
                            ImageViewCompat.setImageTintList(iconDiscover, colorStateList.withAlpha(currentIconAlpha));

                        if (showFeed) ImageViewCompat.setImageTintList(iconFeed, colorStateList.withAlpha(otherIconAlpha));
                    } else {
                        // this changes toolbar title
                        main.mainBinding.toolbar.toolbar.setTitle(slideOffset >= 0.466 ? titleDiscover : main.userQuery);

                        if (showFeed) {
                            imageTintList = ImageViewCompat.getImageTintList(iconFeed);
                            alpha = imageTintList != null ? (imageTintList.getDefaultColor() & 0xFF_000000) >> 24 : 0;

                            if (drawerOpening && alpha > 100)
                                ImageViewCompat.setImageTintList(iconFeed, colorStateList.withAlpha(currentIconAlpha));
                        }

                        ImageViewCompat.setImageTintList(iconDiscover, colorStateList.withAlpha(otherIconAlpha));
                    }
                }

                @Override
                public void onDrawerOpened(@NonNull final View drawerView, @MouseDrawer.EdgeGravity final int gravity) {
                    if (gravity == GravityCompat.START || drawerView == main.mainBinding.feedLayout) {
                        if (currentFeedPlayer != null) {
                            currentFeedPlayer.setPlayWhenReady(true);
                            currentFeedPlayer.getPlaybackState();
                        }
                    } else {
                        // clear selection
                        isSelectionCleared();
                    }
                }

                @Override
                public void onDrawerClosed(@NonNull final View drawerView, @MouseDrawer.EdgeGravity final int gravity) {
                    if (gravity == GravityCompat.START || drawerView == main.mainBinding.feedLayout) {
                        if (currentFeedPlayer != null) {
                            currentFeedPlayer.setPlayWhenReady(false);
                            currentFeedPlayer.getPlaybackState();
                        }
                    } else {
                        // clear selection
                        isSelectionCleared();
                    }
                }
            };

            ImageViewCompat.setImageTintList(iconFeed, colorStateList.withAlpha(100)); // to change colors when created
            ImageViewCompat.setImageTintList(iconProfile, colorStateList.withAlpha(255)); // to change colors when created
            ImageViewCompat.setImageTintList(iconDiscover, colorStateList.withAlpha(100)); // to change colors when created

            main.mainBinding.drawerLayout.addDrawerListener(simpleDrawerListener);
        }

        collapsingToolbar = main.mainBinding.appBarLayout.getChildAt(0);

        main.mainBinding.mainPosts.setNestedScrollingEnabled(false);
        main.mainBinding.highlightsList.setLayoutManager(new LinearLayoutManager(main, LinearLayoutManager.HORIZONTAL, false));
        main.mainBinding.highlightsList.setAdapter(main.highlightsAdapter);

        int color = -1;
        final Drawable background = main.mainBinding.appBarLayout.getBackground();
        if (background instanceof MaterialShapeDrawable) {
            final MaterialShapeDrawable drawable = (MaterialShapeDrawable) background;
            final ColorStateList fillColor = drawable.getFillColor();
            if (fillColor != null) color = fillColor.getDefaultColor();
        } else {
            final Bitmap bitmap = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas();
            canvas.setBitmap(bitmap);
            background.draw(canvas);
            color = bitmap.getPixel(4, 4);
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
        if (color == -1 || color == 0) color = resources.getBoolean(R.bool.isNight) ? 0xff212121 : 0xfff5f5f5;
        main.mainBinding.profileInfo.setBackgroundColor(color);
        main.mainBinding.profileInfo.setClickable(true);
        if (!isBottomToolbar) main.mainBinding.toolbar.toolbar.setBackgroundColor(color);

        main.mainBinding.appBarLayout.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
            private int height;

            @Override
            public void onOffsetChanged(final AppBarLayout appBarLayout, final int verticalOffset) {
                if (height == 0) {
                    height = main.mainBinding.profileInfo.getHeight();
                    collapsingToolbar.setMinimumHeight(height);
                }
                main.mainBinding.profileInfo.setTranslationY(-Math.min(0, verticalOffset));
            }
        });

        main.setSupportActionBar(main.mainBinding.toolbar.toolbar);
        if (isBottomToolbar) {
            final LinearLayout linearLayout = (LinearLayout) main.mainBinding.toolbar.toolbar.getParent();
            linearLayout.removeView(main.mainBinding.toolbar.toolbar);
            linearLayout.addView(main.mainBinding.toolbar.toolbar, linearLayout.getChildCount());
        }

        final GridAutofitLayoutManager layoutManager = new GridAutofitLayoutManager(main, Utils.convertDpToPx(130));
        main.mainBinding.mainPosts.setLayoutManager(layoutManager);
        main.mainBinding.mainPosts.addItemDecoration(new GridSpacingItemDecoration(Utils.convertDpToPx(4)));
        main.mainBinding.mainPosts.setAdapter(postsAdapter = new PostsAdapter(main.allItems, v -> {
            final Object tag = v.getTag();
            if (tag instanceof PostModel) {
                final PostModel postModel = (PostModel) tag;

                if (postsAdapter.isSelecting) toggleSelection(postModel);
                else main.startActivity(new Intent(main, PostViewer.class)
                        .putExtra(Constants.EXTRAS_INDEX, postModel.getPosition())
                        .putExtra(Constants.EXTRAS_POST, postModel)
                        .putExtra(Constants.EXTRAS_USER, main.userQuery)
                        .putExtra(Constants.EXTRAS_TYPE, ItemGetType.MAIN_ITEMS));
            }
        }, v -> { // long click listener
            final Object tag = v.getTag();
            if (tag instanceof PostModel) {
                postsAdapter.isSelecting = true;
                toggleSelection((PostModel) tag);
            }
            return true;
        }));

        this.lazyLoader = new RecyclerLazyLoader(layoutManager, (page, totalItemsCount) -> {
            if ((!autoloadPosts || isHashtag) && hasNextPage) {
                main.mainBinding.swipeRefreshLayout.setRefreshing(true);
                stopCurrentExecutor();
                currentlyExecuting = new PostsFetcher(isHashtag ? main.userQuery : main.profileModel.getId(), endCursor, postsFetchListener)
                        .setUsername(isHashtag ? null : main.profileModel.getUsername())
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                endCursor = null;
            }
        });
        main.mainBinding.mainPosts.addOnScrollListener(lazyLoader);
    }

    private void setupFeed() {
        main.mainBinding.feedStories.setLayoutManager(new LinearLayoutManager(main, LinearLayoutManager.HORIZONTAL, false));
        main.mainBinding.feedStories.setAdapter(feedStoriesAdapter);
        refreshFeedStories();

        final LinearLayoutManager layoutManager = new LinearLayoutManager(main);
        main.mainBinding.feedPosts.setLayoutManager(layoutManager);
        main.mainBinding.feedPosts.setAdapter(feedAdapter = new FeedAdapter(main, main.feedItems, (view, text, isHashtag) ->
                new AlertDialog.Builder(main).setMessage(isHashtag ? R.string.comment_view_mention_hash_search : R.string.comment_view_mention_user_search)
                        .setTitle(text).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ok, (dialog, which) -> {
                    if (Main.scanHack != null) {
                        main.mainBinding.drawerLayout.closeDrawers();
                        Main.scanHack.onResult(text);
                    }
                }).show()));

        main.mainBinding.feedSwipeRefreshLayout.setOnRefreshListener(() -> {
            refreshFeedStories();

            if (feedLazyLoader != null) feedLazyLoader.resetState();
            main.feedItems.clear();
            if (feedAdapter != null) feedAdapter.notifyDataSetChanged();
            new FeedFetcher(feedFetchListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        });

        main.mainBinding.feedPosts.addOnScrollListener(feedLazyLoader = new RecyclerLazyLoader(layoutManager, (page, totalItemsCount) -> {
            if (feedHasNextPage) {
                main.mainBinding.feedSwipeRefreshLayout.setRefreshing(true);
                new FeedFetcher(feedEndCursor, feedFetchListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                feedEndCursor = null;
            }
        }));

        main.mainBinding.feedPosts.addOnScrollListener(new VideoAwareRecyclerScroller(main, main.feedItems,
                (itemPos, player) -> currentFeedPlayer = player));

        new FeedFetcher(feedFetchListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void refreshFeedStories() {
        // todo setup feed stories
        if (prevStoriesFetcher != null) {
            try {
                prevStoriesFetcher.cancel(true);
            } catch (final Exception e) {
                // ignore
            }
        }
        prevStoriesFetcher = new FeedStoriesFetcher(feedStoriesListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void setupExplore() {
        final GridAutofitLayoutManager layoutManager = new GridAutofitLayoutManager(main, Utils.convertDpToPx(130));
        main.mainBinding.discoverPosts.setLayoutManager(layoutManager);
        main.mainBinding.discoverPosts.addItemDecoration(new GridSpacingItemDecoration(Utils.convertDpToPx(4)));

        main.mainBinding.discoverSwipeRefreshLayout.setOnRefreshListener(() -> {
            if (discoverLazyLoader != null) discoverLazyLoader.resetState();
            main.discoverItems.clear();
            if (discoverAdapter != null) discoverAdapter.notifyDataSetChanged();
            new DiscoverFetcher(null, discoverFetchListener, false).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        });

        main.mainBinding.discoverPosts.setAdapter(discoverAdapter = new DiscoverAdapter(main.discoverItems, v -> {
            final Object tag = v.getTag();
            if (tag instanceof DiscoverItemModel) {
                final DiscoverItemModel itemModel = (DiscoverItemModel) tag;

                if (discoverAdapter.isSelecting) toggleDiscoverSelection(itemModel);
                else main.startActivity(new Intent(main, PostViewer.class)
                        .putExtra(Constants.EXTRAS_INDEX, itemModel.getPosition())
                        .putExtra(Constants.EXTRAS_TYPE, ItemGetType.DISCOVER_ITEMS)
                        .putExtra(Constants.EXTRAS_POST, new PostModel(itemModel.getShortCode())));
            }
        }, v -> {
            final Object tag = v.getTag();
            if (tag instanceof DiscoverItemModel) {
                discoverAdapter.isSelecting = true;
                toggleDiscoverSelection((DiscoverItemModel) tag);
            }
            return true;
        }));

        main.mainBinding.discoverPosts.addOnScrollListener(discoverLazyLoader = new RecyclerLazyLoader(layoutManager, (page, totalItemsCount) -> {
            if (discoverHasMore) {
                main.mainBinding.discoverSwipeRefreshLayout.setRefreshing(true);
                new DiscoverFetcher(discoverEndMaxId, discoverFetchListener, false).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                discoverEndMaxId = null;
            }
        }));

        new DiscoverFetcher(null, discoverFetchListener, true).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void onIntent(final Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if (!Utils.isEmpty(action) && !Intent.ACTION_MAIN.equals(action)) {
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

                boolean error = true;

                String data = null;
                final Bundle extras = intent.getExtras();
                if (extras != null) {
                    final Object extraData = extras.get(Intent.EXTRA_TEXT);
                    if (extraData != null) {
                        error = false;
                        data = extraData.toString();
                    }
                }

                if (error) {
                    final Uri intentData = intent.getData();
                    if (intentData != null) data = intentData.toString();
                }

                if (data != null && !Utils.isEmpty(data)) {
                    if (data.indexOf('\n') > 0) data = data.substring(data.lastIndexOf('\n') + 1);

                    final IntentModel model = Utils.stripString(data);
                    if (model != null) {
                        final String modelText = model.getText();
                        final IntentModelType modelType = model.getType();

                        if (modelType == IntentModelType.POST) {
                            main.startActivityForResult(new Intent(main, PostViewer.class)
                                    .putExtra(Constants.EXTRAS_USER, main.userQuery)
                                    .putExtra(Constants.EXTRAS_POST, new PostModel(modelText)), 9629);
                        } else {
                            main.addToStack();
                            main.userQuery = modelType == IntentModelType.HASHTAG ? '#' + modelText : modelText;
                            onRefresh();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onRefresh() {
        main.mainBinding.drawerLayout.closeDrawers();
        if (lazyLoader != null) lazyLoader.resetState();
        stopCurrentExecutor();
        main.allItems.clear();
        main.selectedItems.clear();
        if (postsAdapter != null) {
            postsAdapter.isSelecting = false;
            postsAdapter.notifyDataSetChanged();
        }
        main.mainBinding.appBarLayout.setExpanded(true, true);
        main.mainBinding.privatePage.setVisibility(View.GONE);
        main.mainBinding.mainProfileImage.setImageBitmap(null);
        main.mainBinding.mainProfileImage.setImageDrawable(null);
        main.mainBinding.mainUrl.setText(null);
        main.mainBinding.mainFullName.setText(null);
        main.mainBinding.mainPostCount.setText(null);
        main.mainBinding.mainFollowers.setText(null);
        main.mainBinding.mainFollowing.setText(null);
        main.mainBinding.mainBiography.setText(null);
        main.mainBinding.mainBiography.setEnabled(false);
        main.mainBinding.mainProfileImage.setEnabled(false);
        main.mainBinding.mainBiography.setMentionClickListener(null);
        main.mainBinding.mainUrl.setVisibility(View.GONE);
        main.mainBinding.isVerified.setVisibility(View.GONE);

        main.mainBinding.mainPosts.setNestedScrollingEnabled(false);
        main.mainBinding.highlightsList.setVisibility(View.GONE);
        collapsingToolbar.setVisibility(View.GONE);
        main.highlightsAdapter.setData(null);

        main.mainBinding.swipeRefreshLayout.setRefreshing(main.userQuery != null);
        if (main.userQuery == null) {
            main.mainBinding.toolbar.toolbar.setTitle(R.string.app_name);
            return;
        }

        isHashtag = main.userQuery.charAt(0) == '#';
        collapsingToolbar.setVisibility(isHashtag ? View.GONE : View.VISIBLE);

        if (isHashtag) {
            main.mainBinding.toolbar.toolbar.setTitle(resources.getString(R.string.title_hashtag_prefix) + main.userQuery);
            main.mainBinding.infoContainer.setVisibility(View.GONE);

            currentlyExecuting = new PostsFetcher(main.userQuery, postsFetchListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        } else {
            main.mainBinding.toolbar.toolbar.setTitle(main.userQuery);
            main.mainBinding.infoContainer.setVisibility(View.VISIBLE);

            currentlyExecuting = new ProfileFetcher(main.userQuery, profileModel -> {
                main.profileModel = profileModel;

                if (profileModel == null) {
                    main.mainBinding.swipeRefreshLayout.setRefreshing(false);
                    Toast.makeText(main, R.string.error_loading_profile, Toast.LENGTH_SHORT).show();
                    main.mainBinding.toolbar.toolbar.setTitle(R.string.app_name);
                    return;
                }

                main.mainBinding.isVerified.setVisibility(profileModel.isVerified() ? View.VISIBLE : View.GONE);
                final String profileId = profileModel.getId();

                final boolean isLoggedIn = !Utils.isEmpty(Utils.settingsHelper.getString(Constants.COOKIE));
                if (isLoggedIn) {
                    new StoryStatusFetcher(profileId, result -> {
                        main.storyModels = result;
                        if (result != null && result.length > 0) main.mainBinding.mainProfileImage.setStoriesBorder();
                    }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

                    new HighlightsFetcher(profileId, result -> {
                        if (result != null && result.length > 0) {
                            main.mainBinding.highlightsList.setVisibility(View.VISIBLE);
                            main.highlightsAdapter.setData(result);
                        }
                    }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }

                main.mainBinding.mainProfileImage.setEnabled(false);
                Glide.with(main).load(profileModel.getSdProfilePic()).listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable final GlideException e, final Object model, final Target<Drawable> target, final boolean isFirstResource) {
                        main.mainBinding.mainProfileImage.setEnabled(false);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(final Drawable resource, final Object model, final Target<Drawable> target, final DataSource dataSource, final boolean isFirstResource) {
                        main.mainBinding.mainProfileImage.setEnabled(true);
                        return false;
                    }
                }).into(main.mainBinding.mainProfileImage);

                final long followersCount = profileModel.getFollowersCount();
                final long followingCount = profileModel.getFollowingCount();

                final String postCount = String.valueOf(profileModel.getPostCount());

                SpannableStringBuilder span = new SpannableStringBuilder(resources.getString(R.string.main_posts_count, postCount));
                span.setSpan(new RelativeSizeSpan(1.2f), 0, postCount.length(), 0);
                span.setSpan(new StyleSpan(Typeface.BOLD), 0, postCount.length(), 0);
                main.mainBinding.mainPostCount.setText(span);

                final String followersCountStr = String.valueOf(followersCount);
                final int followersCountStrLen = followersCountStr.length();
                span = new SpannableStringBuilder(resources.getString(R.string.main_posts_followers, followersCountStr));
                span.setSpan(new RelativeSizeSpan(1.2f), 0, followersCountStrLen, 0);
                span.setSpan(new StyleSpan(Typeface.BOLD), 0, followersCountStrLen, 0);
                main.mainBinding.mainFollowers.setText(span);

                final String followingCountStr = String.valueOf(followingCount);
                final int followingCountStrLen = followingCountStr.length();
                span = new SpannableStringBuilder(resources.getString(R.string.main_posts_following, followingCountStr));
                span.setSpan(new RelativeSizeSpan(1.2f), 0, followingCountStrLen, 0);
                span.setSpan(new StyleSpan(Typeface.BOLD), 0, followingCountStrLen, 0);
                main.mainBinding.mainFollowing.setText(span);

                main.mainBinding.mainFullName.setText(profileModel.getName());

                CharSequence biography = profileModel.getBiography();
                main.mainBinding.mainBiography.setCaptionIsExpandable(true);
                main.mainBinding.mainBiography.setCaptionIsExpanded(true);
                if (Utils.hasMentions(biography)) {
                    biography = Utils.getMentionText(biography);
                    main.mainBinding.mainBiography.setText(biography, TextView.BufferType.SPANNABLE);
                    main.mainBinding.mainBiography.setMentionClickListener(mentionClickListener);
                } else {
                    main.mainBinding.mainBiography.setText(biography);
                    main.mainBinding.mainBiography.setMentionClickListener(null);
                }

                final String url = profileModel.getUrl();
                if (Utils.isEmpty(url)) {
                    main.mainBinding.mainUrl.setVisibility(View.GONE);
                } else {
                    main.mainBinding.mainUrl.setVisibility(View.VISIBLE);
                    main.mainBinding.mainUrl.setText(Utils.getSpannableUrl(url));
                }

                main.mainBinding.mainFullName.setSelected(true);
                main.mainBinding.mainBiography.setEnabled(true);

                if (!profileModel.isPrivate()) {
                    main.mainBinding.swipeRefreshLayout.setRefreshing(true);
                    main.mainBinding.mainPosts.setVisibility(View.VISIBLE);
                    main.mainBinding.privatePage.setVisibility(View.GONE);

                    if (isLoggedIn) {
                        final View.OnClickListener followClickListener = v -> main.startActivity(new Intent(main, FollowViewer.class)
                                .putExtra(Constants.EXTRAS_FOLLOWERS, v == main.mainBinding.mainFollowers)
                                .putExtra(Constants.EXTRAS_NAME, profileModel.getUsername())
                                .putExtra(Constants.EXTRAS_ID, profileId));

                        main.mainBinding.mainFollowers.setOnClickListener(followersCount > 0 ? followClickListener : null);
                        main.mainBinding.mainFollowing.setOnClickListener(followingCount > 0 ? followClickListener : null);
                    }

                    currentlyExecuting = new PostsFetcher(profileId, postsFetchListener).setUsername(profileModel.getUsername())
                            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                } else {
                    main.mainBinding.swipeRefreshLayout.setRefreshing(false);
                    main.mainBinding.privatePage.setVisibility(View.VISIBLE);
                    main.mainBinding.mainPosts.setVisibility(View.GONE);
                }
            }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    public static void stopCurrentExecutor() {
        if (currentlyExecuting != null) {
            try {
                currentlyExecuting.cancel(true);
            } catch (final Exception e) {
                if (logCollector != null)
                    logCollector.appendException(e, LogCollector.LogFile.MAIN_HELPER, "stopCurrentExecutor");
                if (BuildConfig.DEBUG) Log.e("AWAISKING_APP", "", e);
            }
        }
    }

    private void toggleSelection(final PostModel postModel) {
        if (postModel != null && postsAdapter != null) {
            if (postModel.isSelected()) main.selectedItems.remove(postModel);
            else main.selectedItems.add(postModel);
            postModel.setSelected(!postModel.isSelected());
            notifyAdapter(postModel);
        }
    }

    private void notifyAdapter(final PostModel postModel) {
        if (main.selectedItems.size() < 1) postsAdapter.isSelecting = false;
        if (postModel.getPosition() < 0) postsAdapter.notifyDataSetChanged();
        else postsAdapter.notifyItemChanged(postModel.getPosition(), postModel);

        if (main.downloadAction != null) main.downloadAction.setVisible(postsAdapter.isSelecting);
    }

    ///////////////////////////////////////////////////
    private void toggleDiscoverSelection(final DiscoverItemModel itemModel) {
        if (itemModel != null && discoverAdapter != null) {
            if (itemModel.isSelected()) main.selectedDiscoverItems.remove(itemModel);
            else main.selectedDiscoverItems.add(itemModel);
            itemModel.setSelected(!itemModel.isSelected());
            notifyDiscoverAdapter(itemModel);
        }
    }

    private void notifyDiscoverAdapter(final DiscoverItemModel itemModel) {
        if (main.selectedDiscoverItems.size() < 1) discoverAdapter.isSelecting = false;
        if (itemModel.getPosition() < 0) discoverAdapter.notifyDataSetChanged();
        else discoverAdapter.notifyItemChanged(itemModel.getPosition(), itemModel);

        if (main.downloadAction != null) main.downloadAction.setVisible(discoverAdapter.isSelecting);
    }

    public boolean isSelectionCleared() {
        if (postsAdapter != null && postsAdapter.isSelecting) {
            for (final PostModel postModel : main.selectedItems) postModel.setSelected(false);
            main.selectedItems.clear();
            postsAdapter.isSelecting = false;
            postsAdapter.notifyDataSetChanged();
            if (main.downloadAction != null) main.downloadAction.setVisible(false);
            return false;
        } else if (discoverAdapter != null && discoverAdapter.isSelecting) {
            for (final DiscoverItemModel itemModel : main.selectedDiscoverItems) itemModel.setSelected(false);
            main.selectedDiscoverItems.clear();
            discoverAdapter.isSelecting = false;
            discoverAdapter.notifyDataSetChanged();
            if (main.downloadAction != null) main.downloadAction.setVisible(false);
            return false;
        }
        return true;
    }

    public void deselectSelection(final BasePostModel postModel) {
        if (postModel instanceof PostModel) {
            main.selectedItems.remove(postModel);
            postModel.setSelected(false);
            if (postsAdapter != null) notifyAdapter((PostModel) postModel);
        } else if (postModel instanceof DiscoverItemModel) {
            main.selectedDiscoverItems.remove(postModel);
            postModel.setSelected(false);
            if (discoverAdapter != null) notifyDiscoverAdapter((DiscoverItemModel) postModel);
        }
    }

    public void onPause() {
        if (currentFeedPlayer != null) {
            currentFeedPlayer.setPlayWhenReady(false);
            currentFeedPlayer.getPlaybackState();
        }
    }

    public void onResume() {
        if (currentFeedPlayer != null) {
            currentFeedPlayer.setPlayWhenReady(true);
            currentFeedPlayer.getPlaybackState();
        }
    }
}