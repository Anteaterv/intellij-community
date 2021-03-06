// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.ui.popup.util;

import com.intellij.openapi.ui.popup.MnemonicNavigationFilter;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.SpeedSearchFilter;
import com.intellij.util.ui.UIUtil;

public abstract class BaseStep<T> implements PopupStep<T>, SpeedSearchFilter<T>, MnemonicNavigationFilter<T> {
  private Runnable myFinalRunnable;

  @Override
  public boolean isSpeedSearchEnabled() {
    return false;
  }

  @Override
  public boolean isAutoSelectionEnabled() {
    return true;
  }

  @Override
  public SpeedSearchFilter<T> getSpeedSearchFilter() {
    return this;
  }

  @Override
  public String getIndexedString(T value) {
    return getTextFor(value);
  }

  @Override
  public boolean isMnemonicsNavigationEnabled() {
    return false;
  }

  @Override
  public int getMnemonicPos(T value) {
    final String text = getTextFor(value);
    int i = text.indexOf("&");
    if (i < 0) {
      i = text.indexOf(UIUtil.MNEMONIC);
    }
    return i;
  }

  @Override
  public MnemonicNavigationFilter<T> getMnemonicNavigationFilter() {
    return this;
  }

  @Override
  public Runnable getFinalRunnable() {
    return myFinalRunnable;
  }

  public PopupStep doFinalStep(Runnable runnable) {
    myFinalRunnable = runnable;
    return FINAL_CHOICE;
  }
}