/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.AsyncTaskLoader;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.widget.CardView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.Qr;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet_BUGS.R;

/**
 * @author Andreas Schildbach
 */
public final class WalletAddressFragment extends Fragment implements NfcAdapter.CreateNdefMessageCallback
{
	private Activity activity;
	private WalletApplication application;
	private LoaderManager loaderManager;
	@Nullable
	private NfcAdapter nfcAdapter;

	private ImageView currentAddressQrView;

	private Bitmap currentAddressQrBitmap = null;
	private Address currentAddressQrAddress = null;
	private final AtomicReference<String> currentAddressUriRef = new AtomicReference<String>();

	private static final int ID_ADDRESS_LOADER = 0;

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = activity;
		this.application = (WalletApplication) activity.getApplication();
		this.loaderManager = getLoaderManager();
		this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		if (nfcAdapter != null && nfcAdapter.isEnabled())
			nfcAdapter.setNdefPushMessageCallback(this, activity);
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		final View view = inflater.inflate(R.layout.wallet_address_fragment, container, false);
		currentAddressQrView = (ImageView) view.findViewById(R.id.bitcoin_address_qr);

		final CardView currentAddressQrCardView = (CardView) view.findViewById(R.id.bitcoin_address_qr_card);
		currentAddressQrCardView.setCardBackgroundColor(Color.WHITE);
		currentAddressQrCardView.setPreventCornerOverlap(false);
		currentAddressQrCardView.setOnClickListener(new OnClickListener()
		{
			@Override
			public void onClick(final View v)
			{
				handleShowQRCode();
			}
		});

		return view;
	}

	@Override
	public void onResume()
	{
		super.onResume();

		loaderManager.initLoader(ID_ADDRESS_LOADER, null, addressLoaderCallbacks);

		updateView();
	}

	@Override
	public void onPause()
	{
		loaderManager.destroyLoader(ID_ADDRESS_LOADER);

		super.onPause();
	}

	private void updateView()
	{
		currentAddressQrView.setImageBitmap(currentAddressQrBitmap);
	}

	private void handleShowQRCode()
	{
		WalletAddressDialogFragment.show(getFragmentManager(), currentAddressQrBitmap, currentAddressQrAddress);
	}

	public static class CurrentAddressLoader extends AsyncTaskLoader<Address>
	{
		private LocalBroadcastManager broadcastManager;
		private final Wallet wallet;

		private static final Logger log = LoggerFactory.getLogger(WalletBalanceLoader.class);

		public CurrentAddressLoader(final Context context, final Wallet wallet)
		{
			super(context);

			this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
			this.wallet = wallet;
		}

		@Override
		protected void onStartLoading()
		{
			super.onStartLoading();

			wallet.addEventListener(walletChangeListener, Threading.SAME_THREAD);
			broadcastManager.registerReceiver(walletChangeReceiver, new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));

			safeForceLoad();
		}

		@Override
		protected void onStopLoading()
		{
			broadcastManager.unregisterReceiver(walletChangeReceiver);
			wallet.removeEventListener(walletChangeListener);
			walletChangeListener.removeCallbacks();

			super.onStopLoading();
		}

		@Override
		protected void onReset()
		{
			broadcastManager.unregisterReceiver(walletChangeReceiver);
			wallet.removeEventListener(walletChangeListener);
			walletChangeListener.removeCallbacks();

			super.onReset();
		}

		@Override
		public Address loadInBackground()
		{
			return wallet.currentReceiveAddress();
		}

		private final ThrottlingWalletChangeListener walletChangeListener = new ThrottlingWalletChangeListener()
		{
			@Override
			public void onThrottledWalletChanged()
			{
				safeForceLoad();
			}
		};

		private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver()
		{
			@Override
			public void onReceive(final Context context, final Intent intent)
			{
				safeForceLoad();
			}
		};

		private void safeForceLoad()
		{
			try
			{
				forceLoad();
			}
			catch (final RejectedExecutionException x)
			{
				log.info("rejected execution: " + CurrentAddressLoader.this.toString());
			}
		}
	}

	private final LoaderCallbacks<Address> addressLoaderCallbacks = new LoaderManager.LoaderCallbacks<Address>()
	{
		@Override
		public Loader<Address> onCreateLoader(final int id, final Bundle args)
		{
			return new CurrentAddressLoader(activity, application.getWallet());
		}

		@Override
		public void onLoadFinished(final Loader<Address> loader, final Address currentAddress)
		{
			if (!currentAddress.equals(currentAddressQrAddress))
			{
				currentAddressQrAddress = currentAddress;

				final String addressStr = BitcoinURI.convertToBitcoinURI(currentAddress, null, null, null);

				final int size = getResources().getDimensionPixelSize(R.dimen.bitmap_dialog_qr_size);
				currentAddressQrBitmap = Qr.bitmap(addressStr, size);

				currentAddressUriRef.set(addressStr);

				updateView();
			}
		}

		@Override
		public void onLoaderReset(final Loader<Address> loader)
		{
		}
	};

	@Override
	public NdefMessage createNdefMessage(final NfcEvent event)
	{
		final String uri = currentAddressUriRef.get();
		if (uri != null)
			return new NdefMessage(new NdefRecord[] { NdefRecord.createUri(uri) });
		else
			return null;
	}
}
