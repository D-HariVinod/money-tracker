package io.github.dharivinod.moneytracker;

import android.app.Activity;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The small pop-up a shake opens over whatever is on screen: amount, category, Save or Cancel.
 * It only adds an entry and shows none of the saved data, so it does not ask for the PIN.
 */
public class QuickAddActivity extends Activity {
    private static final String EXPENSE = "Expense", INCOME = "Income";
    private static final int ROW_DP = 62, GAP_DP = 8;

    private final List<String[]> cats = new ArrayList<>(); // [name, emoji] for the chosen type
    private JSONObject allCats;
    private String type = EXPENSE, category = "";
    private EditText amount;
    private TextView expense, income;
    private final BaseAdapter adapter = new CatAdapter();

    /** False until the page has told the app which categories to show (it does so on first open). */
    static boolean ready(android.content.Context c) {
        return !Quick.categories(c).isEmpty();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            allCats = new JSONObject(Quick.categories(this));
        } catch (JSONException e) {
            finish();
            return;
        }
        setContentView(R.layout.quick_add);
        setFinishOnTouchOutside(false); // only Save or Cancel close it, so a stray tap cannot lose what was typed

        DisplayMetrics m = getResources().getDisplayMetrics();
        getWindow().setLayout(Math.min((int) (m.widthPixels * 0.92f), dp(380)), ViewGroup.LayoutParams.WRAP_CONTENT);

        amount = findViewById(R.id.amount);
        expense = findViewById(R.id.type_expense);
        income = findViewById(R.id.type_income);
        GridView grid = findViewById(R.id.cats);
        int rows = m.heightPixels / m.density >= 700 ? 3 : 2; // leave room for the keyboard on short screens
        grid.getLayoutParams().height = dp(rows * ROW_DP + (rows - 1) * GAP_DP);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            category = cats.get(position)[0];
            adapter.notifyDataSetChanged();
        });

        expense.setOnClickListener(v -> setType(EXPENSE));
        income.setOnClickListener(v -> setType(INCOME));
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
        findViewById(R.id.save).setOnClickListener(v -> save());
        setType(EXPENSE);
        amount.requestFocus();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setType(String t) {
        type = t;
        boolean exp = EXPENSE.equals(t);
        expense.setSelected(exp);
        income.setSelected(!exp);
        expense.setTextColor(getColor(exp ? R.color.red : R.color.ink2));
        income.setTextColor(getColor(exp ? R.color.ink2 : R.color.green));
        cats.clear();
        JSONArray list = allCats.optJSONArray(t);
        boolean keep = false;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONArray c = list.optJSONArray(i);
            if (c == null || c.optString(0).isEmpty()) continue;
            cats.add(new String[]{c.optString(0), c.optString(1)});
            keep |= c.optString(0).equals(category);
        }
        if (!keep) category = "";
        adapter.notifyDataSetChanged();
    }

    private void save() {
        double value;
        try {
            value = Math.round(Double.parseDouble(amount.getText().toString().replaceAll("[^0-9.]", "")) * 100) / 100.0;
        } catch (NumberFormatException e) {
            value = 0;
        }
        if (!(value > 0)) {
            Toast.makeText(this, R.string.quick_need_amount, Toast.LENGTH_SHORT).show();
            amount.requestFocus();
            return;
        }
        if (category.isEmpty()) {
            Toast.makeText(this, R.string.quick_need_category, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Quick.add(this, new JSONObject()
                    .put("type", type)
                    .put("amount", value)
                    .put("category", category)
                    .put("date", LocalDate.now().toString())
                    .put("created", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))));
        } catch (JSONException e) {
            Toast.makeText(this, R.string.quick_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String shown = (INCOME.equals(type) ? "+" : "-") + getString(R.string.rupee) + new DecimalFormat("#,##0.##").format(value);
        Toast.makeText(this, getString(R.string.quick_saved, shown, category), Toast.LENGTH_SHORT).show();
        finish();
    }

    private class CatAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return cats.size();
        }

        @Override
        public Object getItem(int position) {
            return cats.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            TextView chip = recycled instanceof TextView ? (TextView) recycled : newChip();
            String[] c = cats.get(position);
            SpannableString label = new SpannableString(c[1] + "\n" + c[0]);
            label.setSpan(new RelativeSizeSpan(1.7f), 0, c[1].length(), 0); // the emoji, larger than the name
            chip.setText(label);
            chip.setActivated(c[0].equals(category));
            return chip;
        }

        private TextView newChip() {
            TextView chip = new TextView(QuickAddActivity.this);
            chip.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(11);
            chip.setTextColor(getColor(R.color.ink));
            chip.setMaxLines(3);
            chip.setPadding(dp(4), dp(2), dp(4), dp(2));
            chip.setBackgroundResource(R.drawable.chip_bg);
            return chip;
        }
    }
}
