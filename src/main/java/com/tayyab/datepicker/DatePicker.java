package com.ontech.datepicker;

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.NumberPicker.OnValueChangeListener;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Locale;

public class DatePicker extends FrameLayout {
    /**
     * Magic year that represents "no year"
     */
    public static int NO_YEAR = 0;
    public static String language = "en";

    private static final int DEFAULT_START_YEAR = 1900;
    private static final int DEFAULT_END_YEAR = 2800;
    private static final TwoDigitFormatter sTwoDigitFormatter = new TwoDigitFormatter();

    /* UI Components */
    private final LinearLayout mPickerContainer;
    private final NumberPicker mDayPicker;
    private final NumberPicker mMonthPicker;
    private final NumberPicker mYearPicker;

    /**
     * How we notify users the date has changed.
     */
    private OnDateChangedListener mOnDateChangedListener;

    private int mDay;
    private int mMonth;
    private int mYear;
    private Integer mMinDay = null;
    private Integer mMinMonth =  null;
    private Integer mMinYear = null;
    private Integer mMaxDay = null;
    private Integer mMaxMonth = null;
    private Integer mMaxYear = null;
    private boolean mYearOptional;
    private boolean mHasYear;

    /**
     * The callback used to indicate the user changes the date.
     */
    public interface OnDateChangedListener {

        /**
         * @param view        The view associated with this listener.
         * @param year        The year that was set or {@link DatePicker#NO_YEAR} if no year was set
         * @param monthOfYear The month that was set (0-11) for compatibility
         *                    with {@link java.util.Calendar}.
         * @param dayOfMonth  The day of the month that was set.
         */
        void onDateChanged(DatePicker view, int year, int monthOfYear, int dayOfMonth);
    }


    public DatePicker(Context context) {
        this(context, null);
    }

    public DatePicker(Context context, AttributeSet attrs) {
        this(context, attrs, R.style.myDatePickerStyle);
    }

    public DatePicker(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DatePicker(Context context, AttributeSet attrs, int numberPickerStyle, int defStyleRes) {
        super(context, attrs, numberPickerStyle, defStyleRes);

        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.DatePicker, 0, 0);
        language = a.getString(R.styleable.DatePicker_language);

        LayoutInflater inflater = (LayoutInflater) new ContextThemeWrapper(context, numberPickerStyle).getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.date_picker_container, this, true);

        mPickerContainer = findViewById(R.id.parent);

        mDayPicker = inflater.inflate(R.layout.number_picker_day_month, mPickerContainer, true).findViewById(R.id.number_picker);
        mDayPicker.setId(R.id.day);
        mDayPicker.setFormatter(sTwoDigitFormatter);
        mDayPicker.setOnLongPressUpdateInterval(100);
        mDayPicker.setOnValueChangedListener(new OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                mDay = newVal;
                notifyDateChanged();
            }
        });
        mMonthPicker = inflater.inflate(R.layout.number_picker_day_month, mPickerContainer).findViewById(R.id.number_picker);
        mMonthPicker.setId(R.id.month);
        mMonthPicker.setFormatter(sTwoDigitFormatter);

        /*
         * If the user is in a locale where the month names are numeric,
         * use just the number instead of the "month" character for
         * consistency with the other fields.
         */

        mMonthPicker.setOnLongPressUpdateInterval(200);
        mMonthPicker.setOnValueChangedListener(new OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {

                /* We display the month 1-12 but store it 0-11 so always
                 * subtract by one to ensure our internal state is always 0-11
                 */
                mMonth = newVal - 1;
                // Adjust max day of the month
                adjustMaxDay();
                notifyDateChanged();
            }
        });
        mYearPicker = inflater.inflate(R.layout.number_picker_year, mPickerContainer).findViewById(R.id.number_picker);
        mYearPicker.setId(R.id.year);
        mYearPicker.setOnLongPressUpdateInterval(100);
        mYearPicker.setFormatter(sTwoDigitFormatter);
        mYearPicker.setOnValueChangedListener(new OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                mYear = newVal;
                // Adjust max day for leap years if needed
                adjustMaxDay();
                notifyDateChanged();
                updateDaySpinner();
            }
        });
        mYearPicker.setMinValue(DEFAULT_START_YEAR);
        mYearPicker.setMaxValue(DEFAULT_END_YEAR);

        // initialize to current date
        Calendar cal = Calendar.getInstance();
        init(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), null);

        // re-order the number pickers to match the current date format
        reorderPickers();

        mPickerContainer.setLayoutTransition(new LayoutTransition());
        if (!isEnabled()) {
            setEnabled(false);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mDayPicker.setEnabled(enabled);
        mMonthPicker.setEnabled(enabled);
        mYearPicker.setEnabled(enabled);
    }

    private void reorderPickers() {
        // We use numeric spinners for year and day, but textual months. Ask icu4c what
        // order the user's locale uses for that combination. http://b/7207103.
        String skeleton = mHasYear ? "yyyyMMMdd" : "MMMdd";
        String pattern = DateFormat.getBestDateTimePattern(new Locale(language), skeleton);
        char[] order = ICU.getDateFormatOrder(pattern);

        /* Remove the 3 pickers from their parent and then add them back in the
         * required order.
         */
        mPickerContainer.removeAllViews();
        for (char field : order) {
            if (field == 'd') {
                mPickerContainer.addView(mDayPicker);
            } else if (field == 'M') {
                mPickerContainer.addView(mMonthPicker);
            } else {
                // Either 'y' or '\u0000' depending on whether we're showing a year.
                // If we're not showing a year, it doesn't matter where we put it,
                // but the rest of this class assumes that it will be present (but GONE).
                mPickerContainer.addView(mYearPicker);
            }
        }
    }

    public void updateDate(int year, int monthOfYear, int dayOfMonth) {
        if (mYear != year || mMonth != monthOfYear || mDay != dayOfMonth) {
            mYear = (mYearOptional && year == NO_YEAR) ? getCurrentYear() : year;
            mMonth = monthOfYear;
            mDay = dayOfMonth;
            updateSpinners();
            reorderPickers();
            notifyDateChanged();
        }
    }

    private int getCurrentYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    private int getCurrentDay() {
        return Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
    }

    private int getCurrentMonth() {
        return Calendar.getInstance().get(Calendar.MONTH);
    }

    private static class SavedState extends BaseSavedState {

        private final int mYear;
        private final int mMonth;
        private final int mDay;
        private final boolean mHasYear;
        private final boolean mYearOptional;

        /**
         * Constructor called from {@link DatePicker#onSaveInstanceState()}
         */
        private SavedState(Parcelable superState, int year, int month, int day, boolean hasYear,
                           boolean yearOptional) {
            super(superState);
            mYear = year;
            mMonth = month;
            mDay = day;
            mHasYear = hasYear;
            mYearOptional = yearOptional;
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            mYear = in.readInt();
            mMonth = in.readInt();
            mDay = in.readInt();
            mHasYear = in.readInt() != 0;
            mYearOptional = in.readInt() != 0;
        }

        public int getYear() {
            return mYear;
        }

        public int getMonth() {
            return mMonth;
        }

        public int getDay() {
            return mDay;
        }

        public boolean hasYear() {
            return mHasYear;
        }

        public boolean isYearOptional() {
            return mYearOptional;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mYear);
            dest.writeInt(mMonth);
            dest.writeInt(mDay);
            dest.writeInt(mHasYear ? 1 : 0);
            dest.writeInt(mYearOptional ? 1 : 0);
        }

        @SuppressWarnings("unused")
        public static final Parcelable.Creator<SavedState> CREATOR =
                new Creator<SavedState>() {

                    @Override
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }


    /**
     * Override so we are in complete control of save / restore for this widget.
     */
    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        dispatchThawSelfOnly(container);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();

        return new SavedState(superState, mYear, mMonth, mDay, mHasYear, mYearOptional);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mYear = ss.getYear();
        mMonth = ss.getMonth();
        mDay = ss.getDay();
        mHasYear = ss.hasYear();
        mYearOptional = ss.isYearOptional();
        updateSpinners();
    }

    /**
     * Initialize the state.
     *
     * @param year                  The initial year.
     * @param monthOfYear           The initial month.
     * @param dayOfMonth            The initial day of the month.
     * @param onDateChangedListener How user is notified date is changed by user, can be null.
     */
    public void init(int year, int monthOfYear, int dayOfMonth,
                     OnDateChangedListener onDateChangedListener) {
        init(year, monthOfYear, dayOfMonth, false, onDateChangedListener);
    }

    /**
     * Initialize the state.
     *
     * @param year                  The initial year or {@link #NO_YEAR} if no year has been specified
     * @param monthOfYear           The initial month.
     * @param dayOfMonth            The initial day of the month.
     * @param yearOptional          True if the user can toggle the year
     * @param onDateChangedListener How user is notified date is changed by user, can be null.
     */
    public void init(int year, int monthOfYear, int dayOfMonth, boolean yearOptional,
                     OnDateChangedListener onDateChangedListener) {
        mYear = (yearOptional && year == NO_YEAR) ? getCurrentYear() : year;
        mMonth = monthOfYear;
        mDay = dayOfMonth;
        mYearOptional = yearOptional;
        mHasYear = yearOptional ? (year != NO_YEAR) : true;
        mOnDateChangedListener = onDateChangedListener;
        updateSpinners();
    }

    private void updateSpinners() {

        //setting Year Spinner
        mYearPicker.setWrapSelectorWheel(false);
        mYearPicker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        mYearPicker.setMinValue(getCurrentYear());
        mYearPicker.setValue(mYear);
        mYearPicker.setVisibility(mHasYear ? View.VISIBLE : View.GONE);

        //setting Day Spinner
        updateDaySpinner();

        //Setting Month Spinner
        updateMonthSpinner();
    }

    private void updateMonthSpinner() {
        DateFormatSymbols dfs = new DateFormatSymbols(new Locale(language));
        String[] months = dfs.getShortMonths();

        mMonthPicker.setWrapSelectorWheel(false);
        mMonthPicker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
          /*
//         * If the user is in a locale where the month names are numeric,
//         * use just the number instead of the "month" character for
//         * consistency with the other fields.
//         */
        if (months[0].startsWith("1")) {
            for (int i = 0; i < months.length; i++) {
                months[i] = String.valueOf(i + 1);
            }

            //checking the year picker to update the month picker
            if(mMinYear!=null && mYear==mMinYear && mMinDay!=null && mMinDay==mDay){
                mMonthPicker.setMinValue(getCurrentMonth()+1);
            }
            else if (mMinYear != null && mYear != mMinYear) {
                mMonthPicker.setMinValue(1);
            }
            else if(mMinYear!=null && mMinYear==mYear && mMinDay!=null && mMinDay!=mDay){
                mMonthPicker.setMinValue(getCurrentMonth()+1);
            }
            else if(getCurrentMonth()==mMonth){
                mMonthPicker.setMinValue(getCurrentMonth()+1);
            }
            else {
                mMonthPicker.setMinValue(1);
            }
            mMonthPicker.setMaxValue(12);
        } else {

            //checking the year picker to update the month picker
            if(mMinYear!=null && mYear==mMinYear && mMinDay!=null && mMinDay==mDay){
                mMonthPicker.setMinValue(getCurrentMonth()+1);
            }
            else if (mMinYear != null && mYear != mMinYear) {
                mMonthPicker.setDisplayedValues(months);
                mMonthPicker.setMinValue(1);
            }
            else if(mMinYear!=null && mMinYear==mYear && mMinDay!=null && mMinDay!=mDay){
                mMonthPicker.setMinValue(getCurrentMonth()+1);
            }
            else if(getCurrentMonth()==mMonth){
                mMonthPicker.setMinValue(getCurrentMonth()+1);
            }
            mMonthPicker.setMaxValue(12);
            mMonthPicker.setDisplayedValues(getMonths(mMonthPicker.getMinValue()-1, mMonthPicker.getMaxValue(), months));
        }

        /* The month display uses 1-12 but our internal state stores it
         * 0-11 so add one when setting the display.
         */
        mMonthPicker.setValue(mMonth + 1);
    }

    private String[] getMonths(int min, int max, String[] months) {

        String monthsValues[] =  new String[max - min];

        int i = 0;

        for (int j = min; j< max; j++) {

            monthsValues[i] = months[j];
            i+=1;
        }

        return monthsValues;
    }

    private void updateDaySpinner() {
        Calendar cal = Calendar.getInstance();
        // if year was not set, use 2000 as it was a leap year
        cal.set(mHasYear ? mYear : 2000, mMonth, 1);
        int max = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        mDayPicker.setWrapSelectorWheel(false);
        mDayPicker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);

        //condition for checking the month and year picker and setting the day picker.
        if (mMinYear != null && mYear == mMinYear && mMinMonth != null && mMinMonth == mMonth) {
            mDayPicker.setMinValue(getCurrentDay());
        }
        else if (mMinYear != null && mYear != mMinYear || mMinMonth != null && mMinMonth != mMonth) {
            mDayPicker.setMinValue(1);
        }
        else if (getCurrentDay() == mDay) {
            mDayPicker.setMinValue(getCurrentDay());
        }
        else {
            mDayPicker.setMinValue(1);
        }

        mDayPicker.setValue(mDay);
        mDayPicker.setMaxValue(max);
    }

    public int getYear() {
        return (mYearOptional && !mHasYear) ? NO_YEAR : mYear;
    }

    public void setYear(int year) {
        mYear = year;
        updateSpinners();
    }

    public boolean isYearOptional() {
        return mYearOptional;
    }

    public int getMonth() {
        return mMonth;
    }

    public void setMonth(int month) {
        mMonth = month;
        updateSpinners();
    }

    public int getDayOfMonth() {
        return mDay;
    }

    public void setDayOfMonth(int day) {
        mDay = day;
        updateSpinners();
    }

    public void setMinDay(Integer day) {
        this.mMinDay = day;
    }

    public void setMinMonth(Integer month) {
        this.mMinMonth = month;
    }

    public void setMinYear(Integer year) {
        this.mMinYear = year;
    }


    public void setMaxMonth(Integer month) {
        this.mMaxMonth = month;
    }

    public void setMaxDay(Integer day) {
        this.mMaxDay = day;
    }

    public void setMaxYear(Integer year) {
        this.mMaxYear = year;
    }

    private void adjustMaxDay() {
        Calendar cal = Calendar.getInstance();
        // if year was not set, use 2000 as it was a leap year
        cal.set(Calendar.YEAR, mHasYear ? mYear : 2000);
        cal.set(Calendar.MONTH, mMonth);
        int max = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (mDay > max) {
            mDay = max;
        }
    }

    public void notifyDateChanged() {
        int year = (mYearOptional && !mHasYear) ? NO_YEAR : mYear;
        if (mMinYear != null && mMinMonth != null && mMinDay != null) {
            limitMinDate(year);
        }

        if (mMaxYear != null && mMaxDay != null && mMaxMonth != null) {
            limitMaxDate(year);
        }

        if (mOnDateChangedListener != null) {
            mOnDateChangedListener.onDateChanged(DatePicker.this, year, mMonth, mDay);
        }
    }

    private void limitMinDate(int year) {
        if (year < mMinYear) {
            mYear = mMinYear;
            year = mMinYear;
        }
        if (year == mMinYear && mMonth < mMinMonth) {
            mMonth = mMinMonth;
        }
        if (year == mMinYear && mMonth == mMinMonth && mDay < mMinDay) {
            mDay = mMinDay;
        }
        updateSpinners();
    }

    private void limitMaxDate(int year) {
        if (year > mMaxYear) {
            mYear = mMaxYear;
            year = mMaxYear;
        }
        if (year == mMaxYear && mMonth > mMaxMonth) {
            mMonth = mMaxMonth;
        }
        if (year == mMaxYear && mMonth == mMaxMonth && mDay > mMaxDay) {
            mDay = mMaxDay;
        }
        updateSpinners();
    }
}