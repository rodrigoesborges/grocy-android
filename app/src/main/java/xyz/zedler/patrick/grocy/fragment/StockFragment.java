package xyz.zedler.patrick.grocy.fragment;

/*
    This file is part of Grocy Android.

    Grocy Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Grocy Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Grocy Android.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2020 by Patrick Zedler & Dominic Zedler
*/

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.volley.NetworkResponse;
import com.android.volley.VolleyError;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import xyz.zedler.patrick.grocy.MainActivity;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.ScanInputActivity;
import xyz.zedler.patrick.grocy.adapter.StockItemAdapter;
import xyz.zedler.patrick.grocy.adapter.StockPlaceholderAdapter;
import xyz.zedler.patrick.grocy.animator.ItemAnimator;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.behavior.AppBarBehavior;
import xyz.zedler.patrick.grocy.behavior.SwipeBehavior;
import xyz.zedler.patrick.grocy.databinding.FragmentStockBinding;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.ProductOverviewBottomSheetDialogFragment;
import xyz.zedler.patrick.grocy.model.Location;
import xyz.zedler.patrick.grocy.model.MissingItem;
import xyz.zedler.patrick.grocy.model.ProductDetails;
import xyz.zedler.patrick.grocy.model.ProductGroup;
import xyz.zedler.patrick.grocy.model.QuantityUnit;
import xyz.zedler.patrick.grocy.model.ShoppingListItem;
import xyz.zedler.patrick.grocy.model.StockItem;
import xyz.zedler.patrick.grocy.util.AnimUtil;
import xyz.zedler.patrick.grocy.util.ClickUtil;
import xyz.zedler.patrick.grocy.util.Constants;
import xyz.zedler.patrick.grocy.util.IconUtil;
import xyz.zedler.patrick.grocy.util.NumUtil;
import xyz.zedler.patrick.grocy.util.SortUtil;
import xyz.zedler.patrick.grocy.view.FilterChip;
import xyz.zedler.patrick.grocy.view.InputChip;
import xyz.zedler.patrick.grocy.web.WebRequest;

public class StockFragment extends Fragment implements StockItemAdapter.StockItemAdapterListener {

    private final static String TAG = Constants.UI.STOCK;
    private final static boolean DEBUG = true;

    private MainActivity activity;
    private SharedPreferences sharedPrefs;
    private Gson gson = new Gson();
    private GrocyApi grocyApi;
    private AppBarBehavior appBarBehavior;
    private WebRequest request;
    private StockItemAdapter stockItemAdapter;
    private ClickUtil clickUtil = new ClickUtil();
    private AnimUtil animUtil = new AnimUtil();
    private FragmentStockBinding binding;
    private SwipeBehavior swipeBehavior;

    private FilterChip chipExpiring;
    private FilterChip chipExpired;
    private FilterChip chipMissing;
    private EditText editTextSearch;
    private InputChip inputChipFilterLocation;
    private InputChip inputChipFilterProductGroup;

    private ArrayList<StockItem> stockItems;
    private ArrayList<StockItem> expiringItems;
    private ArrayList<StockItem> expiredItems;
    private ArrayList<MissingItem> missingItems;
    private ArrayList<String> shoppingListProductIds;
    private ArrayList<StockItem> missingStockItems;
    private ArrayList<StockItem> filteredItems;
    private ArrayList<StockItem> displayedItems;
    private ArrayList<QuantityUnit> quantityUnits;
    private ArrayList<Location> locations;
    private ArrayList<ProductGroup> productGroups;

    private String search;
    private String itemsToDisplay;
    private String filterProductGroupId;
    private String sortMode;
    private String errorState;
    private int filterLocationId;
    private int daysExpiringSoon;
    private boolean sortAscending;
    private boolean isRestoredInstance;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentStockBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        activity = (MainActivity) getActivity();
        assert activity != null;

        // GET PREFERENCES

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(activity);
        String days = sharedPrefs.getString(
                Constants.PREF.STOCK_EXPIRING_SOON_DAYS,
                String.valueOf(5)
        );
        // ignore server value if not available
        daysExpiringSoon = days == null || days.isEmpty() || days.equals("null")
                ? 5
                : Integer.parseInt(days);
        sortMode = sharedPrefs.getString(Constants.PREF.STOCK_SORT_MODE, Constants.STOCK.SORT.NAME);
        sortAscending = sharedPrefs.getBoolean(Constants.PREF.STOCK_SORT_ASCENDING, true);

        // WEB REQUESTS

        request = new WebRequest(activity.getRequestQueue());
        grocyApi = activity.getGrocy();

        // INITIALIZE VARIABLES

        stockItems = new ArrayList<>();
        expiringItems = new ArrayList<>();
        expiredItems = new ArrayList<>();
        missingItems = new ArrayList<>();
        shoppingListProductIds = new ArrayList<>();
        missingStockItems = new ArrayList<>();
        filteredItems = new ArrayList<>();
        displayedItems = new ArrayList<>();
        quantityUnits = new ArrayList<>();
        locations = new ArrayList<>();
        productGroups = new ArrayList<>();

        itemsToDisplay = Constants.STOCK.FILTER.ALL;
        errorState = Constants.STATE.NONE;
        search = "";
        filterLocationId = -1;
        filterProductGroupId = "";
        isRestoredInstance = false;

        // INITIALIZE VIEWS

        // buttons on offline error page
        binding.linearError.buttonErrorRetry.setOnClickListener(v -> refresh());

        // search
        binding.frameStockSearchClose.setOnClickListener(v -> dismissSearch());
        binding.frameStockSearchScan.setOnClickListener(v -> {
            startActivityForResult(
                    new Intent(activity, ScanInputActivity.class),
                    Constants.REQUEST.SCAN
            );
            dismissSearch();
        });
        editTextSearch = binding.textInputStockSearch.getEditText();
        assert editTextSearch != null;
        editTextSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) {
                search = s.toString();
            }
        });
        editTextSearch.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchItems(editTextSearch.getText().toString());
                activity.hideKeyboard();
                return true;
            } return false;
        });

        // APP BAR BEHAVIOR

        appBarBehavior = new AppBarBehavior(
                activity,
                R.id.linear_stock_app_bar_default,
                R.id.linear_stock_app_bar_search
        );

        // SWIPE REFRESH

        binding.swipeStock.setProgressBackgroundColorSchemeColor(
                ContextCompat.getColor(activity, R.color.surface)
        );
        binding.swipeStock.setColorSchemeColors(
                ContextCompat.getColor(activity, R.color.secondary)
        );
        binding.swipeStock.setOnRefreshListener(this::refresh);

        // CHIPS

        chipExpiring = new FilterChip(
                activity,
                R.color.retro_yellow_bg,
                activity.getString(R.string.msg_expiring_products, 0),
                () -> {
                    chipExpired.changeState(false);
                    chipMissing.changeState(false);
                    filterItems(Constants.STOCK.FILTER.VOLATILE.EXPIRING);
                },
                () -> filterItems(Constants.STOCK.FILTER.ALL)
        );
        chipExpiring.setId(R.id.chip_stock_filter_expiring);
        chipExpired = new FilterChip(
                activity,
                R.color.retro_red_bg_black,
                activity.getString(R.string.msg_expired_products, 0),
                () -> {
                    chipExpiring.changeState(false);
                    chipMissing.changeState(false);
                    filterItems(Constants.STOCK.FILTER.VOLATILE.EXPIRED);
                },
                () -> filterItems(Constants.STOCK.FILTER.ALL)
        );
        chipExpired.setId(R.id.chip_stock_filter_expired);
        chipMissing = new FilterChip(
                activity,
                R.color.retro_blue_bg,
                activity.getString(R.string.msg_missing_products, 0),
                () -> {
                    chipExpiring.changeState(false);
                    chipExpired.changeState(false);
                    filterItems(Constants.STOCK.FILTER.VOLATILE.MISSING);
                },
                () -> filterItems(Constants.STOCK.FILTER.ALL)
        );
        chipMissing.setId(R.id.chip_stock_filter_missing);

        // clear filter containers
        binding.linearStockFilterContainerTop.removeAllViews();
        binding.linearStockFilterContainerBottom.removeAllViews();

        if(isFeatureEnabled(Constants.PREF.FEATURE_STOCK_BBD_TRACKING)) {
            binding.linearStockFilterContainerTop.addView(chipExpiring);
            binding.linearStockFilterContainerTop.addView(chipExpired);
        }
        binding.linearStockFilterContainerTop.addView(chipMissing);

        if(savedInstanceState == null) binding.scrollStock.scrollTo(0, 0);

        binding.recyclerStock.setLayoutManager(
                new LinearLayoutManager(
                        activity,
                        LinearLayoutManager.VERTICAL,
                        false
                )
        );
        binding.recyclerStock.setItemAnimator(new ItemAnimator());
        binding.recyclerStock.setAdapter(new StockPlaceholderAdapter());

        swipeBehavior = new SwipeBehavior(activity) {
            @Override
            public void instantiateUnderlayButton(
                    RecyclerView.ViewHolder viewHolder,
                    List<UnderlayButton> underlayButtons
            ) {
                StockItem stockItem = stockItems.get(viewHolder.getAdapterPosition());
                if(stockItem.getAmount() > 0
                        && stockItem.getProduct().getEnableTareWeightHandling() == 0
                ) {
                    underlayButtons.add(new SwipeBehavior.UnderlayButton(
                            R.drawable.ic_round_consume_product,
                            position -> performAction(
                                    Constants.ACTION.CONSUME,
                                    displayedItems.get(position).getProduct().getId()
                            )
                    ));
                }
                if(stockItem.getAmount()
                        > stockItem.getAmountOpened()
                        && stockItem.getProduct().getEnableTareWeightHandling() == 0
                        && isFeatureEnabled(Constants.PREF.FEATURE_STOCK_OPENED_TRACKING)
                ) {
                    underlayButtons.add(new SwipeBehavior.UnderlayButton(
                            R.drawable.ic_round_open_product,
                            position -> performAction(
                                    Constants.ACTION.OPEN,
                                    displayedItems.get(position).getProduct().getId()
                            )
                    ));
                }
                if(underlayButtons.isEmpty()) {
                    underlayButtons.add(new SwipeBehavior.UnderlayButton(
                            R.drawable.ic_round_close,
                            position -> swipeBehavior.recoverLatestSwipedItem()
                    ));
                }
            }
        };
        swipeBehavior.attachToRecyclerView(binding.recyclerStock);

        if(savedInstanceState == null) {
            load();
        } else {
            restoreSavedInstanceState(savedInstanceState);
        }

        // UPDATE UI

        activity.updateUI(
                Constants.UI.STOCK_DEFAULT,
                (getArguments() == null
                        || getArguments().getBoolean(Constants.ARGUMENT.ANIMATED, true))
                        && savedInstanceState == null,
                TAG
        );
        setArguments(null);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        if(!isHidden()) {
            outState.putParcelableArrayList("stockItems", stockItems);
            outState.putParcelableArrayList("expiringItems", expiringItems);
            outState.putParcelableArrayList("expiredItems", expiredItems);
            outState.putParcelableArrayList("missingItems", missingItems);
            outState.putStringArrayList("shoppingListProducts", shoppingListProductIds);
            outState.putParcelableArrayList("missingStockItems", missingStockItems);
            outState.putParcelableArrayList("filteredItems", filteredItems);
            outState.putParcelableArrayList("displayedItems", displayedItems);
            outState.putParcelableArrayList("quantityUnits", quantityUnits);
            outState.putParcelableArrayList("locations", locations);
            outState.putParcelableArrayList("productGroups", productGroups);

            outState.putString("itemsToDisplay", itemsToDisplay);
            outState.putString("errorState", errorState);
            outState.putString("search", search);
            outState.putInt("filterLocationId", filterLocationId);
            outState.putString("filterProductGroupId", filterProductGroupId);

            appBarBehavior.saveInstanceState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    private void restoreSavedInstanceState(@NonNull Bundle savedInstanceState) {
        if(isHidden()) return;

        errorState = savedInstanceState.getString("errorState", Constants.STATE.NONE);
        setError(errorState, false);
        if(errorState.equals(Constants.STATE.OFFLINE)
                || errorState.equals(Constants.STATE.ERROR)
        ) return;

        stockItems = savedInstanceState.getParcelableArrayList("stockItems");
        expiringItems = savedInstanceState.getParcelableArrayList("expiringItems");
        expiredItems = savedInstanceState.getParcelableArrayList("expiredItems");
        missingItems = savedInstanceState.getParcelableArrayList("missingItems");
        shoppingListProductIds = savedInstanceState.getStringArrayList("shoppingListProducts");
        missingStockItems = savedInstanceState.getParcelableArrayList("missingStockItems");
        filteredItems = savedInstanceState.getParcelableArrayList("filteredItems");
        displayedItems = savedInstanceState.getParcelableArrayList("displayedItems");
        quantityUnits = savedInstanceState.getParcelableArrayList("quantityUnits");
        locations = savedInstanceState.getParcelableArrayList("locations");
        productGroups = savedInstanceState.getParcelableArrayList("productGroups");

        appBarBehavior.restoreInstanceState(savedInstanceState);
        binding.swipeStock.setRefreshing(false);

        // SEARCH
        search = savedInstanceState.getString("search", "");
        editTextSearch.setText(search);

        // FILTERS
        updateLocationFilter(savedInstanceState.getInt("filterLocationId", -1));
        updateProductGroupFilter(
                savedInstanceState.getString("filterProductGroupId", "")
        );
        isRestoredInstance = true;
        filterItems(
                savedInstanceState.getString("itemsToDisplay", Constants.STOCK.FILTER.ALL)
        );

        chipExpiring.setText(
                activity.getString(R.string.msg_expiring_products, expiringItems.size())
        );
        chipExpired.setText(
                activity.getString(R.string.msg_expired_products, expiredItems.size())
        );
        chipMissing.setText(
                activity.getString(R.string.msg_missing_products, missingItems.size())
        );
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if(!hidden) onActivityCreated(null);
    }

    private void load() {
        if(activity.isOnline()) {
            download();
        } else {
            setError(Constants.STATE.OFFLINE, false);
        }
    }

    public void refresh() {
        if(activity.isOnline()) {
            setError(Constants.STATE.NONE, true);
            download();
        } else {
            binding.swipeStock.setRefreshing(false);
            activity.showMessage(
                    Snackbar.make(
                            activity.binding.frameMainContainer,
                            activity.getString(R.string.msg_no_connection),
                            Snackbar.LENGTH_SHORT
                    ).setActionTextColor(
                            ContextCompat.getColor(activity, R.color.secondary)
                    ).setAction(
                            activity.getString(R.string.action_retry),
                            v1 -> refresh()
                    )
            );
        }
    }

    private void setError(String state, boolean animated) {
        errorState = state;

        View viewIn = binding.linearError.linearError;
        View viewOut = binding.scrollStock;

        switch (state) {
            case Constants.STATE.OFFLINE:
                binding.linearError.imageError.setImageResource(R.drawable.illustration_broccoli);
                binding.linearError.textErrorTitle.setText(R.string.error_offline);
                binding.linearError.textErrorSubtitle.setText(R.string.error_offline_subtitle);
                setEmptyState(Constants.STATE.NONE);
                break;
            case Constants.STATE.ERROR:
                binding.linearError.imageError.setImageResource(R.drawable.illustration_popsicle);
                binding.linearError.textErrorTitle.setText(R.string.error_unknown);
                binding.linearError.textErrorSubtitle.setText(R.string.error_unknown_subtitle);
                setEmptyState(Constants.STATE.NONE);
                break;
            case Constants.STATE.NONE:
                viewIn = binding.scrollStock;
                viewOut = binding.linearError.linearError;
                break;
        }

        animUtil.replaceViews(viewIn, viewOut, animated);
    }

    private void setEmptyState(String state) {
        LinearLayout container = binding.linearEmpty.linearEmpty;
        new Handler().postDelayed(() -> {
            switch (state) {
                case Constants.STATE.EMPTY:
                    binding.linearEmpty.imageEmpty.setImageResource(R.drawable.illustration_toast);
                    binding.linearEmpty.textEmptyTitle.setText(R.string.error_empty_stock);
                    binding.linearEmpty.textEmptySubtitle.setText(R.string.error_empty_stock_sub);
                    break;
                case Constants.STATE.NO_SEARCH_RESULTS:
                    binding.linearEmpty.imageEmpty.setImageResource(R.drawable.illustration_jar);
                    binding.linearEmpty.textEmptyTitle.setText(R.string.error_search);
                    binding.linearEmpty.textEmptySubtitle.setText(R.string.error_search_sub);
                    break;
                case Constants.STATE.NO_FILTER_RESULTS:
                    binding.linearEmpty.imageEmpty.setImageResource(R.drawable.illustration_coffee);
                    binding.linearEmpty.textEmptyTitle.setText(R.string.error_filter);
                    binding.linearEmpty.textEmptySubtitle.setText(R.string.error_filter_sub);
                    break;
                case Constants.STATE.NONE:
                    if(container.getVisibility() == View.GONE) return;
                    break;
            }
        }, 125);
        // show new empty state with delay or hide it if NONE
        if(state.equals(Constants.STATE.NONE)) {
            container.animate().alpha(0).setDuration(125).withEndAction(
                    () -> container.setVisibility(View.GONE)
            ).start();
        } else {
            if(container.getVisibility() == View.VISIBLE) {
                // first hide previous empty state if needed
                container.animate().alpha(0).setDuration(125).start();
            }
            new Handler().postDelayed(() -> {
                container.setAlpha(0);
                container.setVisibility(View.VISIBLE);
                container.animate().alpha(1).setDuration(125).start();
            }, 150);
        }
    }

    private void download() {
        binding.swipeStock.setRefreshing(true);
        downloadQuantityUnits();
        downloadLocations();
        downloadProductGroups();
        downloadStock();
        if(sharedPrefs.getBoolean(Constants.PREF.SHOW_SHOPPING_LIST_ICON_IN_STOCK, true)
                && isFeatureEnabled(Constants.PREF.FEATURE_SHOPPING_LIST)
        ) {
            downloadShoppingList();
        }
    }

    private void downloadQuantityUnits() {
        request.get(
                grocyApi.getObjects(GrocyApi.ENTITY.QUANTITY_UNITS),
                TAG,
                response -> {
                    try {
                        quantityUnits = gson.fromJson(
                                response,
                                new TypeToken<List<QuantityUnit>>(){}.getType()
                        );
                    } catch (JsonSyntaxException e) {
                        onDownloadError();
                        return;
                    }
                    if(DEBUG) Log.i(
                            TAG, "downloadQuantityUnits: quantityUnits = " + quantityUnits
                    );
                },
                error -> onDownloadError(),
                this::onQueueEmpty
        );
    }

    private void downloadLocations() {
        request.get(
                grocyApi.getObjects(GrocyApi.ENTITY.LOCATIONS),
                TAG,
                response -> {
                    try {
                        locations = gson.fromJson(
                                response,
                                new TypeToken<List<Location>>(){}.getType()
                        );
                    } catch (JsonSyntaxException e) {
                        onDownloadError();
                        return;
                    }
                    if(DEBUG) Log.i(TAG, "downloadLocations: locations = " + locations);
                    setMenuLocationFilters();
                },
                error -> onDownloadError(),
                this::onQueueEmpty
        );
    }

    private void downloadProductGroups() {
        request.get(
                grocyApi.getObjects(GrocyApi.ENTITY.PRODUCT_GROUPS),
                TAG,
                response -> {
                    try {
                        productGroups = gson.fromJson(
                                response,
                                new TypeToken<List<ProductGroup>>(){}.getType()
                        );
                    } catch (JsonSyntaxException e) {
                        onDownloadError();
                        return;
                    }
                    if(DEBUG) Log.i(
                            TAG, "downloadProductGroups: productGroups = " + productGroups
                    );
                    setMenuProductGroupFilters();
                },
                error -> onDownloadError(),
                this::onQueueEmpty
        );
    }

    private void downloadStock() {
        request.get(
                grocyApi.getStock(),
                TAG,
                response -> {
                    try {
                        stockItems = gson.fromJson(
                                response,
                                new TypeToken<List<StockItem>>(){}.getType()
                        );
                    } catch (JsonSyntaxException e) {
                        onDownloadError();
                        return;
                    }
                    if(DEBUG) Log.i(TAG, "downloadStock: stockItems = " + stockItems);
                    downloadVolatile();
                    for(StockItem stockItem : stockItems) {
                        double minStockAmount = stockItem.getProduct().getMinStockAmount();
                        if(minStockAmount > 0 && stockItem.getAmount() < minStockAmount) {
                            missingStockItems.add(stockItem);
                        }
                    }
                },
                error -> onDownloadError(),
                this::onQueueEmpty
        );
    }

    private void downloadVolatile() {
        request.get(
                grocyApi.getStockVolatile(),
                TAG,
                response -> {
                    if(DEBUG) Log.i(TAG, "downloadVolatile: success");
                    try {
                        JSONObject jsonObject = new JSONObject(response);

                        // Parse first part of volatile array: expiring products
                        expiringItems = gson.fromJson(
                                jsonObject.getJSONArray("expiring_products").toString(),
                                new TypeToken<List<StockItem>>(){}.getType()
                        );
                        if(DEBUG) Log.i(TAG, "downloadVolatile: expiring = " + expiringItems);

                        // Parse second part of volatile array: expired products
                        expiredItems = gson.fromJson(
                                jsonObject.getJSONArray("expired_products").toString(),
                                new TypeToken<List<StockItem>>(){}.getType()
                        );
                        if(DEBUG) Log.i(TAG, "downloadVolatile: expired = " + expiredItems);

                        // Parse third part of volatile array: missing products
                        missingItems = gson.fromJson(
                                jsonObject.getJSONArray("missing_products").toString(),
                                new TypeToken<List<MissingItem>>(){}.getType()
                        );
                        if(DEBUG) Log.i(TAG, "downloadVolatile: missing = " + missingItems);

                    } catch (JSONException e) {
                        Log.e(TAG, "downloadVolatile: " + e);
                    }

                    chipExpiring.setText(
                            activity.getString(R.string.msg_expiring_products, expiringItems.size())
                    );
                    chipExpired.setText(
                            activity.getString(R.string.msg_expired_products, expiredItems.size())
                    );
                    chipMissing.setText(
                            activity.getString(R.string.msg_missing_products, missingItems.size())
                    );

                    downloadMissingProductDetails();
                },
                error -> onDownloadError(),
                this::onQueueEmpty
        );
    }

    private void downloadMissingProductDetails() {
        for(MissingItem missingItem : missingItems) {
            // Filter missing item if it is partly in stock or already in stock overview
            if(missingItem.getIsPartlyInStock() == 1) continue;
            boolean isInStock = false;
            for(StockItem stockItem : stockItems) {
                if (stockItem.getProductId() == missingItem.getId()) {
                    isInStock = true;
                    break;
                }
            }
            if(isInStock) continue;

            request.get(
                    grocyApi.getStockProductDetails(missingItem.getId()),
                    TAG,
                    response -> {
                        ProductDetails productDetails = gson.fromJson(
                                response,
                                new TypeToken<ProductDetails>(){}.getType()
                        );
                        if(DEBUG) Log.i(
                                TAG,
                                "downloadMissingProductDetails: "
                                        + "name = " + productDetails.getProduct().getName()
                        );
                        StockItem stockItem = new StockItem(productDetails);
                        stockItems.add(stockItem);
                        missingStockItems.add(stockItem);
                    },
                    error -> onDownloadError(),
                    this::onQueueEmpty
            );
        }
    }

    private void downloadShoppingList() {
        request.get(
                grocyApi.getObjects(GrocyApi.ENTITY.SHOPPING_LIST),
                TAG,
                response -> {
                    ArrayList<ShoppingListItem> shoppingListItems = gson.fromJson(
                            response,
                            new TypeToken<List<ShoppingListItem>>(){}.getType()
                    );
                    shoppingListProductIds = new ArrayList<>();
                    if(shoppingListItems != null && !shoppingListItems.isEmpty()) {
                        for(ShoppingListItem item : shoppingListItems) {
                            if(item.getProductId() != null && !item.getProductId().isEmpty()) {
                                shoppingListProductIds.add(item.getProductId());
                            }
                        }
                    }
                    if(DEBUG) Log.i(
                            TAG,
                            "downloadShoppingList: shoppingListItems = " + shoppingListItems
                            + ", productIds = " + shoppingListProductIds
                    );
                },
                error -> onDownloadError(),
                this::onQueueEmpty
        );
    }

    private void onQueueEmpty() {
        binding.swipeStock.setRefreshing(false);
        filterItems(itemsToDisplay);
    }

    private void onDownloadError() {
        request.cancelAll(TAG);
        binding.swipeStock.setRefreshing(false);
        setError(Constants.STATE.ERROR, true);
    }

    private void filterItems(String filter) {
        itemsToDisplay = filter.isEmpty() ? Constants.STOCK.FILTER.ALL : filter;
        if(DEBUG) Log.i(
                TAG, "filterItems: filter = " + filter + ", display = " + itemsToDisplay
        );
        // VOLATILE
        switch (itemsToDisplay) {
            case Constants.STOCK.FILTER.VOLATILE.EXPIRING:
                filteredItems = this.expiringItems;
                break;
            case Constants.STOCK.FILTER.VOLATILE.EXPIRED:
                filteredItems = this.expiredItems;
                break;
            case Constants.STOCK.FILTER.VOLATILE.MISSING:
                filteredItems = this.missingStockItems;
                break;
            default:
                filteredItems = this.stockItems;
                break;
        }
        if(DEBUG) Log.i(TAG, "filterItems: filteredItems = " + filteredItems);
        // LOCATION
        if(filterLocationId != -1) {
            ArrayList<StockItem> tempItems = new ArrayList<>();
            for(StockItem stockItem : filteredItems) {
                if(filterLocationId == stockItem.getProduct().getLocationId()) {
                    tempItems.add(stockItem);
                }
            }
            filteredItems = tempItems;
        }
        // PRODUCT GROUP
        if(!filterProductGroupId.isEmpty()) {
            ArrayList<StockItem> tempItems = new ArrayList<>();
            for(StockItem stockItem : filteredItems) {
                if(filterProductGroupId.equals(stockItem.getProduct().getProductGroupId())) {
                    tempItems.add(stockItem);
                }
            }
            filteredItems = tempItems;
        }
        // SEARCH
        if(!search.isEmpty()) { // active search
            searchItems(search);
        } else {
            // EMPTY STATES
            if(filteredItems.isEmpty()) {
                String state = Constants.STATE.EMPTY;
                if(itemsToDisplay.equals(Constants.STOCK.FILTER.VOLATILE.EXPIRING)
                        || itemsToDisplay.equals(Constants.STOCK.FILTER.VOLATILE.EXPIRED)
                        || itemsToDisplay.equals(Constants.STOCK.FILTER.VOLATILE.MISSING)
                        || filterLocationId != -1
                        || !filterProductGroupId.isEmpty()
                ) {
                    state = Constants.STATE.NO_FILTER_RESULTS;
                }
                setEmptyState(state);
            } else {
                setEmptyState(Constants.STATE.NONE);
            }

            // SORTING
            if(displayedItems != filteredItems || isRestoredInstance) {
                displayedItems = filteredItems;
                sortItems(sortMode, sortAscending);
            }
            isRestoredInstance = false;
        }
    }

    private void searchItems(String search) {
        search = search.toLowerCase();
        if(DEBUG) Log.i(TAG, "searchItems: search = " + search);
        this.search = search;
        if(search.isEmpty()) {
            filterItems(itemsToDisplay);
        } else { // only if search contains something
            ArrayList<StockItem> searchedItems = new ArrayList<>();
            for(StockItem stockItem : filteredItems) {
                String name = stockItem.getProduct().getName();
                String description = stockItem.getProduct().getDescription();
                name = name != null ? name.toLowerCase() : "";
                description = description != null ? description.toLowerCase() : "";
                if(name.contains(search) || description.contains(search)) {
                    searchedItems.add(stockItem);
                }
            }
            setEmptyState(
                    searchedItems.isEmpty()
                            ? Constants.STATE.NO_SEARCH_RESULTS
                            : Constants.STATE.NONE
            );
            if(displayedItems != searchedItems) {
                displayedItems = searchedItems;
                sortItems(sortMode, sortAscending);
            }
        }
    }

    private void filterLocation(Location location) {
        if(filterLocationId != location.getId()) { // only if not already selected
            if(DEBUG) Log.i(TAG, "filterLocation: " + location);
            filterLocationId = location.getId();
            if(inputChipFilterLocation != null) {
                inputChipFilterLocation.changeText(location.getName());
            } else {
                inputChipFilterLocation = new InputChip(
                        activity,
                        location.getName(),
                        R.drawable.ic_round_place,
                        true,
                        () -> {
                            filterLocationId = -1;
                            inputChipFilterLocation = null;
                            filterItems(itemsToDisplay);
                        });
                binding.linearStockFilterContainerBottom.addView(inputChipFilterLocation);
            }
            filterItems(itemsToDisplay);
        } else {
            if(DEBUG) Log.i(TAG, "filterLocation: " + location + " already filtered");
        }
    }

    /**
     * Sets the location filter without filtering
     */
    private void updateLocationFilter(int filterLocationId) {
        Location location = getLocation(filterLocationId);
        if(location == null) return;

        this.filterLocationId = filterLocationId;
        if(inputChipFilterLocation != null) {
            inputChipFilterLocation.changeText(location.getName());
        } else {
            inputChipFilterLocation = new InputChip(
                    activity,
                    location.getName(),
                    R.drawable.ic_round_place,
                    true,
                    () -> {
                        this.filterLocationId = -1;
                        inputChipFilterLocation = null;
                        filterItems(itemsToDisplay);
                    });
            binding.linearStockFilterContainerBottom.addView(inputChipFilterLocation);
        }
    }

    private void filterProductGroup(ProductGroup productGroup) {
        if(!filterProductGroupId.equals(String.valueOf(productGroup.getId()))) {
            if(DEBUG) Log.i(TAG, "filterProductGroup: " + productGroup);
            filterProductGroupId = String.valueOf(productGroup.getId());
            if(inputChipFilterProductGroup != null) {
                inputChipFilterProductGroup.changeText(productGroup.getName());
            } else {
                inputChipFilterProductGroup = new InputChip(
                        activity,
                        productGroup.getName(),
                        R.drawable.ic_round_category,
                        true,
                        () -> {
                            filterProductGroupId = "";
                            inputChipFilterProductGroup = null;
                            filterItems(itemsToDisplay);
                        });
                binding.linearStockFilterContainerBottom.addView(inputChipFilterProductGroup);
            }
            filterItems(itemsToDisplay);
        } else {
            if(DEBUG) Log.i(TAG, "filterProductGroup: " + productGroup + " already filtered");
        }
    }

    /**
     * Sets the product group filter without filtering
     */
    private void updateProductGroupFilter(String filterProductGroupId) {
        ProductGroup productGroup = getProductGroup(filterProductGroupId);
        if(productGroup == null) return;

        this.filterProductGroupId = filterProductGroupId;
        if(inputChipFilterProductGroup != null) {
            inputChipFilterProductGroup.changeText(productGroup.getName());
        } else {
            inputChipFilterProductGroup = new InputChip(
                    activity,
                    productGroup.getName(),
                    R.drawable.ic_round_category,
                    true,
                    () -> {
                        this.filterProductGroupId = "";
                        inputChipFilterProductGroup = null;
                        filterItems(itemsToDisplay);
                    });
            binding.linearStockFilterContainerBottom.addView(inputChipFilterProductGroup);
        }
    }

    private void sortItems(String sortMode, boolean ascending) {
        if(DEBUG) Log.i(TAG, "sortItems: sort by " + sortMode + ", ascending = " + ascending);
        this.sortMode = sortMode;
        sortAscending = ascending;
        sharedPrefs.edit()
                .putString(Constants.PREF.STOCK_SORT_MODE, sortMode)
                .putBoolean(Constants.PREF.STOCK_SORT_ASCENDING, ascending)
                .apply();
        switch (sortMode) {
            case Constants.STOCK.SORT.NAME:
                SortUtil.sortStockItemsByName(displayedItems, ascending);
                break;
            case Constants.STOCK.SORT.BBD:
                SortUtil.sortStockItemsByBBD(displayedItems, ascending);
                break;
        }
        refreshAdapter();
    }

    private void sortItems(String sortMode) {
        sortItems(sortMode, sortAscending);
    }

    private void sortItems(boolean ascending) {
        sortItems(sortMode, ascending);
    }

    @SuppressWarnings({"rawtypes"})
    private void refreshAdapter() {
        binding.recyclerStock.animate().alpha(0).setDuration(150).withEndAction(() -> {
            RecyclerView.Adapter adapterCurrent = binding.recyclerStock.getAdapter();

            if(adapterCurrent != null && adapterCurrent.getClass() != StockItemAdapter.class) {
                stockItemAdapter = new StockItemAdapter(
                        activity,
                        displayedItems,
                        quantityUnits,
                        shoppingListProductIds,
                        daysExpiringSoon,
                        sortMode,
                        isFeatureEnabled(Constants.PREF.FEATURE_STOCK_BBD_TRACKING),
                        this
                );
                binding.recyclerStock.setAdapter(stockItemAdapter);
            } else {
                stockItemAdapter.setSortMode(sortMode);
                stockItemAdapter.updateData(displayedItems, shoppingListProductIds);
                stockItemAdapter.notifyDataSetChanged();
            }
            binding.recyclerStock.animate().alpha(1).setDuration(150).start();
        }).start();
    }

    private void loadProductDetailsByBarcode(String barcode) {
        request.get(
                grocyApi.getStockProductByBarcode(barcode),
                response -> {
                    ProductDetails productDetails = gson.fromJson(
                            response,
                            new TypeToken<ProductDetails>(){}.getType()
                    );
                    showProductOverview(productDetails);
                }, error -> {
                    NetworkResponse response = error.networkResponse;
                    Snackbar snackbar;
                    if(response != null && response.statusCode == 400) {
                        snackbar = Snackbar.make(
                                activity.binding.frameMainContainer,
                                activity.getString(R.string.msg_not_found),
                                Snackbar.LENGTH_SHORT
                        );
                    } else {
                        snackbar = Snackbar.make(
                                activity.binding.frameMainContainer,
                                activity.getString(R.string.msg_error),
                                Snackbar.LENGTH_SHORT
                        );
                    }
                    activity.showMessage(snackbar);
                }
        );
    }

    /**
     * Called from product details BottomSheet when button was pressed
     * @param action Constants.ACTION
     */
    public void performAction(String action, int productId) {
        switch (action) {
            case Constants.ACTION.CONSUME:
                consumeProduct(productId, 1, false);
                break;
            case Constants.ACTION.OPEN:
                openProduct(productId);
                break;
            case Constants.ACTION.CONSUME_ALL:
                StockItem stockItem = getStockItem(productId);
                if(stockItem != null) {
                    consumeProduct(
                            productId,
                            stockItem.getProduct().getEnableTareWeightHandling() == 0
                                    ? stockItem.getAmount()
                                    : stockItem.getProduct().getTareWeight(),
                            false
                    );
                }
                break;
            case Constants.ACTION.CONSUME_SPOILED:
                consumeProduct(productId, 1, true);
                break;
        }
    }

    private void consumeProduct(int productId, double amount, boolean spoiled) {
        JSONObject body = new JSONObject();
        try {
            body.put("amount", amount);
            body.put("transaction_type", "consume");
            body.put("spoiled", spoiled);
        } catch (JSONException e) {
            Log.e(TAG, "consumeProduct: " + e);
        }
        Log.i(TAG, "consumeProduct: " + activity);
        request.post(
                grocyApi.consumeProduct(productId),
                body,
                response -> {
                    String transactionId = null;
                    try {
                        transactionId = response.getString("transaction_id");
                    } catch (JSONException e) {
                        Log.e(TAG, "consumeProduct: " + e);
                    }

                    int index = getProductPosition(productId);
                    StockItem stockItem = displayedItems.get(index);

                    updateConsumedStockItem(
                            index,
                            stockItem,
                            spoiled,
                            transactionId,
                            false
                    );
                },
                error -> {
                    showErrorMessage(error);
                    if(DEBUG) Log.i(TAG, "consumeProduct: " + error);
                }
        );
    }

    private void updateConsumedStockItem(
            int index,
            StockItem stockItemOld,
            boolean spoiled,
            String transactionId,
            boolean undo
    ) {
        request.get(
                grocyApi.getStockProductDetails(stockItemOld.getProductId()),
                response -> {

                    // get up-to-date server amount from response
                    ProductDetails productDetails = gson.fromJson(
                            response,
                            new TypeToken<ProductDetails>(){}.getType()
                    );
                    // create updated stockItem object
                    StockItem stockItemNew = new StockItem(productDetails);

                    if(!undo && stockItemNew.getAmount() == 0
                            && stockItemNew.getProduct().getMinStockAmount() == 0
                    ) {
                        displayedItems.remove(index);
                        stockItemAdapter.notifyItemRemoved(index);
                    } else if(undo && stockItemOld.getAmount() == 0
                            && stockItemOld.getProduct().getMinStockAmount() == 0
                    ) {
                        displayedItems.add(index, stockItemNew);
                        stockItemAdapter.notifyItemInserted(index);
                    } else {
                        stockItemAdapter.notifyItemChanged(index);
                        displayedItems.set(index, stockItemNew);
                    }

                    // create snackBar with info for undo or with info after undo
                    Snackbar snackbar;
                    if(!undo) {

                        // calculate consumed amount for info
                        double amountConsumed = stockItemOld.getAmount() - stockItemNew.getAmount();

                        QuantityUnit quantityUnit = productDetails.getQuantityUnitStock();

                        snackbar = Snackbar.make(
                                activity.binding.frameMainContainer,
                                activity.getString(
                                        spoiled
                                                ? R.string.msg_consumed_spoiled
                                                : R.string.msg_consumed,
                                        NumUtil.trim(amountConsumed),
                                        quantityUnit != null
                                                ? amountConsumed == 1
                                                ? quantityUnit.getName()
                                                : quantityUnit.getNamePlural()
                                                : "",
                                        stockItemNew.getProduct().getName()
                                ), Snackbar.LENGTH_LONG
                        );

                        // set undo button on snackBar
                        if(transactionId != null) {
                            snackbar.setActionTextColor(
                                    ContextCompat.getColor(activity, R.color.secondary)
                            ).setAction(
                                    activity.getString(R.string.action_undo),
                                    // on success, this method will be executed again to update
                                    v -> request.post(
                                            grocyApi.undoStockTransaction(transactionId),
                                            response1 -> updateConsumedStockItem(
                                                    index,
                                                    stockItemNew,
                                                    spoiled,
                                                    null,
                                                    true
                                            ),
                                            this::showErrorMessage
                                    )
                            );
                        }
                        if(DEBUG) Log.i(
                                TAG, "updateConsumedStockItem: consumed " + amountConsumed
                        );
                    } else {
                        snackbar = Snackbar.make(
                                activity.binding.frameMainContainer,
                                activity.getString(R.string.msg_undone_transaction),
                                Snackbar.LENGTH_SHORT
                        );
                        if(DEBUG) Log.i(TAG, "updateConsumedStockItem: undone");
                    }
                    activity.showMessage(snackbar);
                },
                error -> {
                    showErrorMessage(error);
                    if(DEBUG) Log.i(TAG, "updateConsumedStockItem: " + error);
                }
        );
    }

    private void openProduct(int productId) {
        JSONObject body = new JSONObject();
        try {
            double amount = 1;
            body.put("amount", amount);
        } catch (JSONException e) {
            Log.e(TAG, "openProduct: " + e);
        }
        request.post(
                grocyApi.openProduct(productId),
                body,
                response -> {
                    String transactionId = null;
                    try {
                        transactionId = response.getString("transaction_id");
                    } catch (JSONException e) {
                        Log.e(TAG, "openProduct: " + e);
                    }

                    int index = getProductPosition(productId);
                    updateOpenedStockItem(index, productId, transactionId, false);
                },
                error -> {
                    showErrorMessage(error);
                    if(DEBUG) Log.i(TAG, "openProduct: " + error);
                }
        );
    }

    private void updateOpenedStockItem(
            int index,
            int productId,
            String transactionId,
            boolean undo
    ) {
        request.get(
                grocyApi.getStockProductDetails(productId),
                response -> {

                    // get up-to-date server amount from response
                    ProductDetails productDetails = gson.fromJson(
                            response,
                            new TypeToken<ProductDetails>() {
                            }.getType()
                    );
                    // create updated stockItem object
                    StockItem stockItem = new StockItem(productDetails);

                    displayedItems.set(index, stockItem);
                    stockItemAdapter.notifyItemChanged(index);

                    // create snackBar with info for undo or with info after undo
                    Snackbar snackbar;
                    if(!undo) {

                        QuantityUnit quantityUnit = productDetails.getQuantityUnitStock();
                        snackbar = Snackbar.make(
                                activity.binding.frameMainContainer,
                                activity.getString(
                                        R.string.msg_opened,
                                        NumUtil.trim(1),
                                        quantityUnit != null
                                                ? quantityUnit.getName()
                                                : "",
                                        stockItem.getProduct().getName()
                                ),
                                Snackbar.LENGTH_LONG
                        );

                        // set undo button on snackBar
                        if (transactionId != null) {
                            snackbar.setActionTextColor(
                                    ContextCompat.getColor(activity, R.color.secondary)
                            ).setAction(
                                    activity.getString(R.string.action_undo),
                                    v -> request.post(
                                            grocyApi.undoStockTransaction(transactionId),
                                            response1 -> updateOpenedStockItem(
                                                    index,
                                                    productId,
                                                    transactionId,
                                                    true
                                            ),
                                            this::showErrorMessage
                                    )
                            );
                        }
                        if(DEBUG) Log.i(TAG, "updateOpenedStockItem: opened 1");
                    } else {
                        snackbar = Snackbar.make(
                                activity.binding.frameMainContainer,
                                activity.getString(R.string.msg_undone_transaction),
                                Snackbar.LENGTH_SHORT
                        );
                        if(DEBUG) Log.i(TAG, "updateOpenedStockItem: undone");
                    }
                    activity.showMessage(snackbar);
                },
                error -> {
                    showErrorMessage(error);
                    if(DEBUG) Log.i(TAG, "updateOpenedStockItem: " + error);
                }
        );
    }

    private StockItem getStockItem(int productId) {
        for(StockItem stockItem : displayedItems) {
            if(stockItem.getProduct().getId() == productId) {
                return stockItem;
            }
        } return null;
    }

    /**
     * Returns index in the displayed items.
     * Used for providing a safe and up-to-date value
     * e.g. when the items are filtered/sorted before server responds
     */
    private int getProductPosition(int productId) {
        for(int i = 0; i < displayedItems.size(); i++) {
            if(displayedItems.get(i).getProduct().getId() == productId) {
                return i;
            }
        }
        return 0;
    }

    private QuantityUnit getQuantityUnit(int id) {
        for(QuantityUnit quantityUnit : quantityUnits) {
            if(quantityUnit.getId() == id) {
                return quantityUnit;
            }
        } return null;
    }

    private Location getLocation(int id) {
        for(Location location : locations) {
            if(location.getId() == id) {
                return location;
            }
        } return null;
    }

    private ProductGroup getProductGroup(String id) {
        if(id == null || id.isEmpty()) return null;
        for(ProductGroup productGroup : productGroups) {
            if(productGroup.getId() == Integer.parseInt(id)) {
                return productGroup;
            }
        } return null;
    }

    private void showErrorMessage(VolleyError error) {
        activity.showMessage(
                Snackbar.make(
                        activity.binding.frameMainContainer,
                        activity.getString(R.string.msg_error),
                        Snackbar.LENGTH_SHORT
                )
        );
    }

    private void setMenuLocationFilters() {
        MenuItem menuItem = activity.getBottomMenu().findItem(R.id.action_filter_location);
        if(menuItem != null) {
            SubMenu menuLocations = menuItem.getSubMenu();
            menuLocations.clear();
            SortUtil.sortLocationsByName(locations, true);
            for(Location location : locations) {
                menuLocations.add(location.getName()).setOnMenuItemClickListener(item -> {
                    //if(!uiMode.equals(Constants.UI.STOCK_DEFAULT)) return false;
                    filterLocation(location);
                    return true;
                });
            }
            menuItem.setVisible(!locations.isEmpty());
        }
    }

    private void setMenuProductGroupFilters() {
        MenuItem menuItem = activity.getBottomMenu().findItem(R.id.action_filter_product_group);
        if(menuItem != null) {
            SubMenu menuProductGroups = menuItem.getSubMenu();
            menuProductGroups.clear();
            SortUtil.sortProductGroupsByName(productGroups, true);
            for(ProductGroup productGroup : productGroups) {
                menuProductGroups.add(productGroup.getName()).setOnMenuItemClickListener(item -> {
                    //if(!uiMode.equals(Constants.UI.STOCK_DEFAULT)) return false;
                    filterProductGroup(productGroup);
                    return true;
                });
            }
            menuItem.setVisible(!productGroups.isEmpty());
        }
    }

    private void setMenuSorting() {
        String sortMode = sharedPrefs.getString(
                Constants.PREF.STOCK_SORT_MODE, Constants.STOCK.SORT.NAME
        );
        assert sortMode != null;
        SubMenu menuSort = activity.getBottomMenu().findItem(R.id.action_sort).getSubMenu();
        MenuItem sortName = menuSort.findItem(R.id.action_sort_name);
        MenuItem sortBBD = menuSort.findItem(R.id.action_sort_bbd);
        MenuItem sortAscending = menuSort.findItem(R.id.action_sort_ascending);
        switch (sortMode) {
            case Constants.STOCK.SORT.NAME:
                sortName.setChecked(true);
                break;
            case Constants.STOCK.SORT.BBD:
                sortBBD.setChecked(true);
                break;
        }
        sortAscending.setChecked(
                sharedPrefs.getBoolean(Constants.PREF.STOCK_SORT_ASCENDING, true)
        );
        // ON MENU ITEM CLICK
        sortName.setOnMenuItemClickListener(item -> {
            if(!item.isChecked()) {
                item.setChecked(true);
                sortItems(Constants.STOCK.SORT.NAME);
            }
            return true;
        });
        sortBBD.setOnMenuItemClickListener(item -> {
            if(!item.isChecked()) {
                item.setChecked(true);
                sortItems(Constants.STOCK.SORT.BBD);
            }
            return true;
        });
        sortAscending.setOnMenuItemClickListener(item -> {
            item.setChecked(!item.isChecked());
            sortItems(item.isChecked());
            return true;
        });
    }

    public void setUpBottomMenu() {
        setMenuLocationFilters();
        setMenuProductGroupFilters();
        setMenuSorting();
        MenuItem search = activity.getBottomMenu().findItem(R.id.action_search);
        if(search != null) {
            search.setOnMenuItemClickListener(item -> {
                IconUtil.start(item);
                setUpSearch();
                return true;
            });
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if(requestCode == Constants.REQUEST.SCAN && resultCode == Activity.RESULT_OK) {
            if(data != null) {
                loadProductDetailsByBarcode(data.getStringExtra(Constants.EXTRA.SCAN_RESULT));
            }
        }
    }

    @Override
    public void onItemRowClicked(int position) {
        if(clickUtil.isDisabled()) return;
        // STOCK ITEM CLICK
        swipeBehavior.recoverLatestSwipedItem();
        showProductOverview(displayedItems.get(position));
    }

    private void showProductOverview(StockItem stockItem) {
        if(stockItem != null) {
            QuantityUnit quantityUnit = getQuantityUnit(stockItem.getProduct().getQuIdStock());
            Location location = getLocation(stockItem.getProduct().getLocationId());
            Bundle bundle = new Bundle();
            bundle.putBoolean(Constants.ARGUMENT.SHOW_ACTIONS, true);
            bundle.putParcelable(Constants.ARGUMENT.STOCK_ITEM, stockItem);
            bundle.putParcelable(Constants.ARGUMENT.QUANTITY_UNIT, quantityUnit);
            bundle.putParcelable(Constants.ARGUMENT.LOCATION, location);
            activity.showBottomSheet(new ProductOverviewBottomSheetDialogFragment(), bundle);
        }
    }

    private void showProductOverview(ProductDetails productDetails) {
        if(productDetails != null) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARGUMENT.PRODUCT_DETAILS, productDetails);
            bundle.putBoolean(Constants.ARGUMENT.SHOW_ACTIONS, true);
            activity.showBottomSheet(
                    new ProductOverviewBottomSheetDialogFragment(),
                    bundle
            );
        }
    }

    private void setUpSearch() {
        if(search.isEmpty()) { // only if no search is active
            appBarBehavior.replaceLayout(R.id.linear_stock_app_bar_search, true);
            editTextSearch.setText("");
        }
        binding.textInputStockSearch.requestFocus();
        activity.showKeyboard(editTextSearch);

        activity.setUI(Constants.UI.STOCK_SEARCH);
    }

    public void dismissSearch() {
        appBarBehavior.replaceLayout(R.id.linear_stock_app_bar_default, true);
        activity.hideKeyboard();
        search = "";
        filterItems(itemsToDisplay);

        setEmptyState(Constants.STATE.NONE);

        activity.setUI(Constants.UI.STOCK_DEFAULT);
    }

    private boolean isFeatureEnabled(String pref) {
        if(pref == null) return true;
        return sharedPrefs.getBoolean(pref, true);
    }

    @NonNull
    @Override
    public String toString() {
        return TAG;
    }
}
