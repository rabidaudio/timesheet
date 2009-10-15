/***
 * Copyright (c) 2009 Tasty Cactus Software, LLC
 * 
 * All rights reserved.
 */

package com.tastycactus.timesheet;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TabActivity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.SimpleCursorAdapter;
import android.widget.TabHost;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import com.tastycactus.timesheet.TimesheetDatabase;
import com.tastycactus.timesheet.MergeAdapter;

public class TimeEntriesActivity extends TabActivity
{
    class TimeEntriesWeeklyData 
    {
        TimesheetDatabase m_db;
        int m_year, m_month, m_day;

        // In perl: $data[$day][$i] = { _id => $id, title => $title, duration => $duration }
        Vector<Vector<HashMap<String, String>>> m_data = new Vector<Vector<HashMap<String,String>>>();
        Vector<HashMap<String, String>> m_totals = new Vector<HashMap<String, String>>();

        public TimeEntriesWeeklyData(TimesheetDatabase db, int year, int month, int day) {
            m_db = db;
            m_year = year;
            m_month = month;
            m_day = day;
            for (int i=0; i < 7; ++i) {
                m_data.add(i, new Vector<HashMap<String, String>>());
            }
            requery();
        }

        public void setDate(int year, int month, int day) {
            m_year = year;
            m_month = month;
            m_day = day;
        }

        public void requery() {
            for (int i=0; i < 7; ++i) {
                m_data.get(i).clear();
            }
            m_totals.clear();

            Cursor c = m_db.getWeekEntries(m_year, m_month, m_day);

            HashMap<String, Float> total_map = new HashMap<String, Float>();
            while (!c.isAfterLast()) {
                HashMap<String, String> row_data = new HashMap<String, String>();
                int day = c.getInt(c.getColumnIndex("day"));
                row_data.put("_id", c.getString(c.getColumnIndex("_id")));
                String title = c.getString(c.getColumnIndex("title"));
                row_data.put("title", title);
                float duration = c.getFloat(c.getColumnIndex("duration"));
                row_data.put("duration", String.format("%1.2f", duration));
                m_data.get(day).add(row_data);

                // Track the total durations
                if (total_map.containsKey(title)) {
                    total_map.put(title, total_map.get(title) + duration);
                } else {
                    total_map.put(title, duration);
                }

                c.moveToNext();
            }
            for (Map.Entry<String, Float> entry : total_map.entrySet()) {
                HashMap<String, String> row = new HashMap<String, String>();
                row.put("title", entry.getKey());
                row.put("duration", String.format("%1.2f", entry.getValue()));
                m_totals.add(row);
            }
            c.close();
        }

        public Vector<HashMap<String, String>> entries(int day) {
            return m_data.get(day);
        }

        public Vector<HashMap<String, String>> totals() {
            return m_totals;
        }
    }

    TimeEntriesWeeklyData m_week_data;
    TimesheetDatabase m_db;
    Cursor m_day_cursor;
    TabHost m_tab_host;
    SimpleCursorAdapter m_day_ca;
    SimpleAdapter m_week_adapters[] = new SimpleAdapter[7];
    MergeAdapter m_merge_adapter;
    SimpleAdapter m_totals_adapter;
    Button m_day_button, m_week_button;

    public static final int ADD_TIME_ENTRY_MENU_ITEM    = Menu.FIRST;
    public static final int DELETE_TIME_ENTRY_MENU_ITEM = Menu.FIRST + 1;
    public static final int EDIT_TIME_ENTRY_MENU_ITEM   = Menu.FIRST + 2;
    public static final int LIST_TASKS_MENU_ITEM        = Menu.FIRST + 3;

    private static final int SELECT_DAY_DIALOG_ID = 0;
    private static final int SELECT_WEEK_DIALOG_ID = 1;

    private static final int ACTIVITY_CREATE = 0;
    private static final int ACTIVITY_EDIT   = 1;

    private DatePickerDialog.OnDateSetListener m_day_listener =
        new DatePickerDialog.OnDateSetListener() {
            public void onDateSet(DatePicker view, int year, int month, int day) {
                m_day_cursor.close();
                m_day_cursor = m_db.getTimeEntries(year, month + 1, day);
                m_day_ca.changeCursor(m_day_cursor);
                m_day_button.setText(String.format("%04d-%02d-%02d", year, month + 1, day));
            }
        };

    private DatePickerDialog.OnDateSetListener m_week_listener =
        new DatePickerDialog.OnDateSetListener() {
            public void onDateSet(DatePicker view, int year, int month, int day) {
                m_week_data.setDate(year, month + 1, day);
                m_week_data.requery();
                m_merge_adapter.notifyDataSetChanged();
                m_totals_adapter.notifyDataSetChanged();
                m_week_button.setText(String.format("Week of %04d-%02d-%02d", year, month + 1, day));
            }
        };

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.entries);

        m_tab_host = getTabHost();
        m_tab_host.addTab(m_tab_host.newTabSpec("tab_byday").setIndicator("By Day").setContent(R.id.byday_content));
        m_tab_host.addTab(m_tab_host.newTabSpec("tab_byweek").setIndicator("By Week").setContent(R.id.byweek_content));
        m_tab_host.setCurrentTab(0);

        m_db = new TimesheetDatabase(this);

        setupDayTab();
        setupWeekTab();
    }

    @Override
    public void onDestroy()
    {
        m_day_cursor.close();
        m_db.close();
        super.onDestroy();
    }

    protected void setupDayTab() 
    {
        m_day_cursor = m_db.getTimeEntries();

        ListView time_entry_list = (ListView) findViewById(R.id.entries_byday);
        m_day_ca = new SimpleCursorAdapter(this,
                R.layout.time_entry, 
                m_day_cursor,
                new String[] {"title", "start_time", "end_time"},
                new int[] {R.id.time_entry_title, R.id.time_entry_start, R.id.time_entry_end});
        time_entry_list.setAdapter(m_day_ca);
        time_entry_list.setChoiceMode(ListView.CHOICE_MODE_NONE);

        time_entry_list.setOnItemClickListener( new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent i = new Intent(parent.getContext(), TimeEntryEditActivity.class);
                i.putExtra("_id", id);
                startActivityForResult(i, ACTIVITY_EDIT);
            }
        });

        m_day_button = (Button) findViewById(R.id.day_selection_button);
        m_day_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showDialog(SELECT_DAY_DIALOG_ID);
            }
        });
        final Calendar c = Calendar.getInstance();
        m_day_button.setText(String.format("%04d-%02d-%02d", 
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)));

        registerForContextMenu(time_entry_list);
    }

    protected void setupWeekTab() 
    {
        final Calendar c = Calendar.getInstance();
        m_week_data = new TimeEntriesWeeklyData(m_db,
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));

        ListView week_list = (ListView) findViewById(R.id.entries_byweek);

        for (int i=0; i < 7; ++i) {
            // The weeks in sqlite start on Monday, but Sunday is day 0, so the week
            // actually goes from day 1 to day 6, then day 0
            int day = i == 6 ? 0 : i + 1;
            m_week_adapters[i] = new SimpleAdapter(this,
                    m_week_data.entries(day),
                    R.layout.week_entry,
                    new String[] {"title", "duration"},
                    new int[] {R.id.week_entry_title, R.id.week_entry_duration});
        }

        //MergeAdapter(Context ctx, int header_layout_id, int header_view_id, Adapter[] adapter_list, String[] header_list)
        m_merge_adapter = new MergeAdapter(this, R.layout.header, R.id.header, m_week_adapters,
                new String[] {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"});
        week_list.setAdapter(m_merge_adapter);
        week_list.setChoiceMode(ListView.CHOICE_MODE_NONE);
        week_list.setItemsCanFocus(false);

        ListView total_view = (ListView) findViewById(R.id.entries_week_totals);
        m_totals_adapter = new SimpleAdapter(this,
                m_week_data.totals(),
                R.layout.week_entry,
                new String[] {"title", "duration"},
                new int[] {R.id.week_entry_title, R.id.week_entry_duration});
        total_view.setAdapter(m_totals_adapter);
        total_view.setChoiceMode(ListView.CHOICE_MODE_NONE);
        total_view.setItemsCanFocus(false);

        m_week_button = (Button) findViewById(R.id.week_selection_button);
        m_week_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showDialog(SELECT_WEEK_DIALOG_ID);
            }
        });
        m_week_button.setText(String.format("Week of %04d-%02d-%02d", 
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)));

    }

    @Override
    protected Dialog onCreateDialog(int id) 
    {
        final Calendar c = Calendar.getInstance();
        switch (id) {
            case SELECT_DAY_DIALOG_ID:
                return new DatePickerDialog(this, m_day_listener, 
                        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            case SELECT_WEEK_DIALOG_ID:
                return new DatePickerDialog(this, m_week_listener, 
                        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        }
        return null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) 
    {
        boolean result = super.onCreateOptionsMenu(menu);
        menu.add(Menu.NONE, ADD_TIME_ENTRY_MENU_ITEM, Menu.NONE, "Add Time Entry");
        menu.add(Menu.NONE, LIST_TASKS_MENU_ITEM, Menu.NONE, "List Tasks");
        return result;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) 
    {
        switch (item.getItemId()) {
            case ADD_TIME_ENTRY_MENU_ITEM:
                addTimeEntry();
                return true;
            case LIST_TASKS_MENU_ITEM:
                listTasks();
                return true;
        }
        return false;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) 
    {
        super.onCreateContextMenu(menu, v, menuInfo);
        menu.add(Menu.NONE, DELETE_TIME_ENTRY_MENU_ITEM, Menu.NONE, "Delete Time Entry");
        menu.add(Menu.NONE, EDIT_TIME_ENTRY_MENU_ITEM, Menu.NONE, "Edit Time Entry");
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) 
    {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        switch (item.getItemId()) {
            case DELETE_TIME_ENTRY_MENU_ITEM:
                m_db.deleteTimeEntry(info.id);
                m_day_cursor.requery();
                return true;
            case EDIT_TIME_ENTRY_MENU_ITEM:
                Intent i = new Intent(this, TimeEntryEditActivity.class);
                i.putExtra("_id", info.id);
                startActivityForResult(i, ACTIVITY_EDIT);
                return true;
        }
        return false;
    }

    public void addTimeEntry()
    {
        Intent i = new Intent(this, TimeEntryEditActivity.class);
        startActivityForResult(i, ACTIVITY_CREATE);
    }

    private void listTasks()
    {
        Intent i = new Intent(this, TimesheetActivity.class);
        startActivity(i);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case ACTIVITY_CREATE:
                if (resultCode == RESULT_OK) {
                    m_day_cursor.requery();
                }
                break;
            case ACTIVITY_EDIT:
                if (resultCode == RESULT_OK) {
                    m_day_cursor.requery();
                }
                break;
        }
    }
}