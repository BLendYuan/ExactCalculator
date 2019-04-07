/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calculator2;

import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Build;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

/**
 * TextView adapted for Calculator display.
 */
public class CalculatorText extends AlignedTextView implements MenuItem.OnMenuItemClickListener {

    public static final String TAG_ACTION_MODE = "ACTION_MODE";

    // Temporary paint for use in layout methods.
    private final TextPaint mTempPaint = new TextPaint();

    private final float mMaximumTextSize;
    private final float mMinimumTextSize;
    private final float mStepTextSize;

    private int mWidthConstraint = -1;
    private ActionMode mActionMode;
    private ActionMode.Callback mPasteActionModeCallback;
    private ContextMenu mContextMenu;
    private OnPasteListener mOnPasteListener;
    private OnTextSizeChangeListener mOnTextSizeChangeListener;

    public CalculatorText(Context context) {
        this(context, null /* attrs */);
    }

    public CalculatorText(Context context, AttributeSet attrs) {
        this(context, attrs, 0 /* defStyleAttr */);
    }

    public CalculatorText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.CalculatorText, defStyleAttr, 0);
        mMaximumTextSize = a.getDimension(
                R.styleable.CalculatorText_maxTextSize, getTextSize());
        mMinimumTextSize = a.getDimension(
                R.styleable.CalculatorText_minTextSize, getTextSize());
        mStepTextSize = a.getDimension(R.styleable.CalculatorText_stepTextSize,
                (mMaximumTextSize - mMinimumTextSize) / 3);
        a.recycle();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setupActionMode();
        } else {
            setupContextMenu();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!isLaidOut()) {
            // Prevent shrinking/resizing with our variable textSize.
            setTextSizeInternal(TypedValue.COMPLEX_UNIT_PX, mMaximumTextSize,
                    false /* notifyListener */);
            setMinimumHeight(getLineHeight() + getCompoundPaddingBottom()
                    + getCompoundPaddingTop());
        }

        // Ensure we are at least as big as our parent.
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        if (getMinimumWidth() != width) {
            setMinimumWidth(width);
        }

        // Re-calculate our textSize based on new width.
        mWidthConstraint = MeasureSpec.getSize(widthMeasureSpec)
                - getPaddingLeft() - getPaddingRight();
        final float textSize = getVariableTextSize(getText());
        if (getTextSize() != textSize) {
            setTextSizeInternal(TypedValue.COMPLEX_UNIT_PX, textSize, false /* notifyListener */);
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);

        setTextSize(TypedValue.COMPLEX_UNIT_PX, getVariableTextSize(text.toString()));
    }

    private void setTextSizeInternal(int unit, float size, boolean notifyListener) {
        final float oldTextSize = getTextSize();
        super.setTextSize(unit, size);
        if (notifyListener && mOnTextSizeChangeListener != null && getTextSize() != oldTextSize) {
            mOnTextSizeChangeListener.onTextSizeChanged(this, oldTextSize);
        }
    }

    @Override
    public void setTextSize(int unit, float size) {
        setTextSizeInternal(unit, size, true);
    }

    public float getMinimumTextSize() {
        return mMinimumTextSize;
    }

    public float getMaximumTextSize() {
        return mMaximumTextSize;
    }

    public float getVariableTextSize(CharSequence text) {
        if (mWidthConstraint < 0 || mMaximumTextSize <= mMinimumTextSize) {
            // Not measured, bail early.
            return getTextSize();
        }

        // Capture current paint state.
        mTempPaint.set(getPaint());

        // Step through increasing text sizes until the text would no longer fit.
        float lastFitTextSize = mMinimumTextSize;
        while (lastFitTextSize < mMaximumTextSize) {
            mTempPaint.setTextSize(Math.min(lastFitTextSize + mStepTextSize, mMaximumTextSize));
            if (Layout.getDesiredWidth(text, mTempPaint) > mWidthConstraint) {
                break;
            }
            lastFitTextSize = mTempPaint.getTextSize();
        }

        return lastFitTextSize;
    }

    private static boolean startsWith(CharSequence whole, CharSequence prefix) {
        int wholeLen = whole.length();
        int prefixLen = prefix.length();
        if (prefixLen > wholeLen) {
            return false;
        }
        for (int i = 0; i < prefixLen; ++i) {
            if (prefix.charAt(i) != whole.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Functionally equivalent to setText(), but explicitly announce changes.
     * If the new text is an extension of the old one, announce the addition.
     * Otherwise, e.g. after deletion, announce the entire new text.
     */
    public void changeTextTo(CharSequence newText) {
        final CharSequence oldText = getText();
        final char separator = KeyMaps.translateResult(",").charAt(0);
        final CharSequence added = StringUtils.getExtensionIgnoring(newText, oldText, separator);
        if (added != null) {
            if (added.length() == 1) {
                // The algorithm for pronouncing a single character doesn't seem
                // to respect our hints.  Don't give it the choice.
                final char c = added.charAt(0);
                final int id = KeyMaps.keyForChar(c);
                final String descr = KeyMaps.toDescriptiveString(getContext(), id);
                if (descr != null) {
                    announceForAccessibility(descr);
                } else {
                    announceForAccessibility(String.valueOf(c));
                }
            } else if (added.length() != 0) {
                announceForAccessibility(added);
            }
        } else {
            announceForAccessibility(newText);
        }
        setText(newText, BufferType.SPANNABLE);
    }

    public boolean stopActionModeOrContextMenu() {
        if (mActionMode != null) {
            mActionMode.finish();
            return true;
        }
        if (mContextMenu != null) {
            mContextMenu.close();
            return true;
        }
        return false;
    }

    public void setOnTextSizeChangeListener(OnTextSizeChangeListener listener) {
        mOnTextSizeChangeListener = listener;
    }

    public void setOnPasteListener(OnPasteListener listener) {
        mOnPasteListener = listener;
    }

    /**
     * Use ActionMode for paste support on M and higher.
     */
    @TargetApi(Build.VERSION_CODES.M)
    private void setupActionMode() {
        mPasteActionModeCallback = new ActionMode.Callback2() {

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (onMenuItemClick(item)) {
                    mode.finish();
                    return true;
                } else {
                    return false;
                }
            }

            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                mode.setTag(TAG_ACTION_MODE);
                final MenuInflater inflater = mode.getMenuInflater();
                return createPasteMenu(inflater, menu);
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                mActionMode = null;
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                super.onGetContentRect(mode, view, outRect);
                outRect.top += getTotalPaddingTop();
                outRect.right -= getTotalPaddingRight();
                outRect.bottom -= getTotalPaddingBottom();
                // Encourage menu positioning towards the right, possibly over formula.
                outRect.left = outRect.right;
            }
        };
        setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                mActionMode = startActionMode(mPasteActionModeCallback, ActionMode.TYPE_FLOATING);
                return true;
            }
        });
    }

    /**
     * Use ContextMenu for paste support on L and lower.
     */
    private void setupContextMenu() {
        setOnCreateContextMenuListener(new OnCreateContextMenuListener() {
            @Override
            public void onCreateContextMenu(ContextMenu contextMenu, View view,
                    ContextMenu.ContextMenuInfo contextMenuInfo) {
                final MenuInflater inflater = new MenuInflater(getContext());
                createPasteMenu(inflater, contextMenu);
                mContextMenu = contextMenu;
                for(int i = 0; i < contextMenu.size(); i++) {
                    contextMenu.getItem(i).setOnMenuItemClickListener(CalculatorText.this);
                }
            }
        });
        setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return showContextMenu();
            }
        });
    }

    private boolean createPasteMenu(MenuInflater inflater, Menu menu) {
        final ClipboardManager clipboard = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()) {
            bringPointIntoView(length());
            inflater.inflate(R.menu.paste, menu);
            return true;
        }
        // Prevents the selection action mode on double tap.
        return false;
    }

    private void paste() {
        final ClipboardManager clipboard = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData primaryClip = clipboard.getPrimaryClip();
        if (primaryClip != null && mOnPasteListener != null) {
            mOnPasteListener.onPaste(primaryClip);
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.menu_paste) {
            paste();
            return true;
        }
        return false;
    }

    public interface OnTextSizeChangeListener {
        void onTextSizeChanged(TextView textView, float oldSize);
    }

    public interface OnPasteListener {
        boolean onPaste(ClipData clip);
    }
}
