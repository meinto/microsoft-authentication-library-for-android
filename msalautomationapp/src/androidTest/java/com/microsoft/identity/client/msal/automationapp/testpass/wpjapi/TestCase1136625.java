package com.microsoft.identity.client.msal.automationapp.testpass.wpjapi;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObjectNotFoundException;

import com.microsoft.identity.client.AcquireTokenParameters;
import com.microsoft.identity.client.Prompt;
import com.microsoft.identity.client.claims.ClaimsRequest;
import com.microsoft.identity.client.claims.RequestedClaimAdditionalInformation;
import com.microsoft.identity.client.msal.automationapp.R;
import com.microsoft.identity.client.msal.automationapp.interaction.InteractiveRequest;
import com.microsoft.identity.client.msal.automationapp.interaction.OnInteractionRequired;
import com.microsoft.identity.client.msal.automationapp.testpass.broker.AbstractMsalBrokerTest;
import com.microsoft.identity.client.ui.automation.broker.BrokerHost;
import com.microsoft.identity.client.ui.automation.broker.BrokerMicrosoftAuthenticator;
import com.microsoft.identity.client.ui.automation.broker.ITestBroker;
import com.microsoft.identity.client.ui.automation.browser.BrowserChrome;
import com.microsoft.identity.client.ui.automation.installer.LocalApkInstaller;
import com.microsoft.identity.client.ui.automation.interaction.PromptHandlerParameters;
import com.microsoft.identity.client.ui.automation.interaction.PromptParameter;
import com.microsoft.identity.client.ui.automation.interaction.microsoftsts.AadPromptHandler;
import com.microsoft.identity.client.ui.automation.utils.UiAutomatorUtils;
import com.microsoft.identity.internal.testutils.labutils.LabConfig;
import com.microsoft.identity.internal.testutils.labutils.LabConstants;
import com.microsoft.identity.internal.testutils.labutils.LabUserQuery;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

public class TestCase1136625 extends AbstractMsalBrokerTest {

    @Test
    public void test_1136625() throws InterruptedException, UiObjectNotFoundException {
        final String username = mLoginHint;
        final String password = LabConfig.getCurrentLabConfig().getLabUserPassword();

        BrowserChrome chrome = new BrowserChrome();
        chrome.clear();

        ITestBroker sBroker = new BrokerHost();
        sBroker.install();
        //broker1.perform DeviceRegistration.
        sBroker.performDeviceRegistration(username, password);
        //broker2.getDeviceId()
        //launchMsalTestApp and acquiretoken.
        final CountDownLatch latch = new CountDownLatch(1);

        final AcquireTokenParameters parameters = new AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(mActivity)
                .withLoginHint(null)
                .withResource(mScopes[0])
                .withCallback(successfulInteractiveCallback(latch))
                .withPrompt(Prompt.SELECT_ACCOUNT)
                .build();

        final InteractiveRequest interactiveRequest = new InteractiveRequest(
                mApplication,
                parameters,
                new OnInteractionRequired() {
                    @Override
                    public void handleUserInteraction() {
                        final PromptHandlerParameters promptHandlerParameters = PromptHandlerParameters.builder()
                                .prompt(PromptParameter.SELECT_ACCOUNT)
                                .loginHint(null)
                                .sessionExpected(true)
                                .consentPageExpected(false)
                                .speedBumpExpected(false)
                                .broker(mBroker)
                                .expectingBrokerAccountChooserActivity(true)
                                .registerPageExpected(false)
                                .build();

                        new AadPromptHandler(promptHandlerParameters)
                                .handlePrompt(username, password);
                    }
                }
        );

        interactiveRequest.execute();
        latch.await();

        //close and relaunch the broker.
        sBroker.launch();
        sBroker.obtainDeviceId();

        //installing latest version of Microsoft authenticator app.
        ITestBroker localBrokerAuthenticator = new BrokerMicrosoftAuthenticator(new LocalApkInstaller());
        localBrokerAuthenticator.install();

        //relaunching BrokerHost.
        sBroker.launch();
        //do some operations on sBroker.

        //installing Certificate in the brokerHost.
        //UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiAutomatorUtils.handleButtonClick("com.microsoft.identity.testuserapp:id/buttonInstallCert");
        UiAutomatorUtils.handleButtonClick("android:id/button1");


        //acquiring token with claims.
        final CountDownLatch interactiveLatch = new CountDownLatch(1);

        final ClaimsRequest claimsRequest = new ClaimsRequest();
        final RequestedClaimAdditionalInformation requestedClaimAdditionalInformation =
                new RequestedClaimAdditionalInformation();

        requestedClaimAdditionalInformation.setEssential(true);

        // request the deviceid claim in ID Token
        claimsRequest.requestClaimInIdToken("deviceid", requestedClaimAdditionalInformation);


        final AcquireTokenParameters interactiveParameters = new AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(mActivity)
                .withLoginHint(null)
                .withResource(mScopes[0])
                .withCallback(successfulInteractiveCallback(interactiveLatch))
                .withPrompt(Prompt.SELECT_ACCOUNT)
                .build();

        performTLSOpeation(username, password);

        interactiveLatch.wait();

    }

    private void performTLSOpeation(final String username, final String password) throws UiObjectNotFoundException {

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        // click on Open up from chrome tabs to open in google chrome.
        UiAutomatorUtils.handleButtonClick("com.android.chrome:id/menu_button");
        UiObject openTabs = UiAutomatorUtils.obtainUiObjectWithExactText("Open up");
        Assert.assertTrue(openTabs.exists());
        openTabs.click();

        // in url removing x-client-SKU=MSAL.Android.
        UiObject urlBar = UiAutomatorUtils.obtainUiObjectWithResourceId("com.android.chrome:id/url_bar");
        Assert.assertTrue(urlBar.exists());
        String url = urlBar.getText();
        url = url.replace("x-client-SKU=MSAL.Android", "");

        // entering the final url in google chrome.
        UiAutomatorUtils.handleButtonClick("com.android.chrome:id/delete_button");
        urlBar.setText(url);
        device.pressEnter();

        // entering credentials.
        UiAutomatorUtils.handleInput("i0116", username);
        UiAutomatorUtils.handleButtonClick("idSIButton9");

        UiAutomatorUtils.handleInput("i0118", password);
        UiAutomatorUtils.handleButtonClick("idSIButton9");

        //installing certificate.
        UiAutomatorUtils.handleButtonClick("android:id/button1");
    }

    @Override
    public String[] getScopes() {
        return new String[]{"User.read"};
    }

    @Override
    public String getAuthority() {
        return mApplication.getConfiguration().getDefaultAuthority().getAuthorityURL().toString();
    }

    @Override
    public int getConfigFileResourceId() {
        return R.raw.msal_config_default;
    }

    @Override
    public LabUserQuery getLabUserQuery() {
        return null;
    }

    @Override
    public String getTempUserType() {
        return LabConstants.TempUserType.BASIC;
    }
}
