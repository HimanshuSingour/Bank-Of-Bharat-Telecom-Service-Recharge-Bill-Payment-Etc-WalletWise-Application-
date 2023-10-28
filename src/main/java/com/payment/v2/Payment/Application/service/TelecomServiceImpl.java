package com.payment.v2.Payment.Application.service;

import com.payment.v2.Payment.Application.Notification.NotificationsUtility;
import com.payment.v2.Payment.Application.dto.*;
import com.payment.v2.Payment.Application.entity.AccountInformation;
import com.payment.v2.Payment.Application.dto.MobileRecharge.RechargeRequest;
import com.payment.v2.Payment.Application.dto.MobileRecharge.RechargeResponse;
import com.payment.v2.Payment.Application.entity.RechargePlanes;
import com.payment.v2.Payment.Application.entity.ServiceProvider;
import com.payment.v2.Payment.Application.exceptions.RechargePlanNotFoundException;
import com.payment.v2.Payment.Application.exceptions.ServiceIsDownException;
import com.payment.v2.Payment.Application.exceptions.ServiceProviderIsNullException;
import com.payment.v2.Payment.Application.exceptions.ServiceProviderValidationException;
import com.payment.v2.Payment.Application.repository.RechangeRepositories;
import com.payment.v2.Payment.Application.repository.ServiceProviderRepositories;
import com.twilio.Twilio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

import static com.payment.v2.Payment.Application.config.ExternalServices.URL_FOR_ACCOUNT_SERVICE;
import static com.payment.v2.Payment.Application.config.ExternalServices.URL_FOR_ACCOUNT_UPDATE_SERVICE;
import static com.payment.v2.Payment.Application.constants.MobileRechargeConstant.*;

@Service
public class TelecomServiceImpl implements TelecomService {

    @Autowired
    private ServiceProviderRepositories serviceProviderRepositories;

    @Autowired
    private RechangeRepositories rechangeRepositories;

    @Autowired
    private NotificationsUtility notificationsUtility;

    @Autowired
    private RestTemplate restTemplate;


    static {
        Twilio.init(TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN);
    }


    // For Client (Add Provider Info)
    @Override
    public ServiceProvider addServiceProvides(ServiceProvider serviceProvider) {

        if (serviceProvider == null) {
            throw new ServiceProviderIsNullException("Service Provider Detail is not Found..");
        }

        if (serviceProvider.getServiceProviderName() == null || serviceProvider.getServiceProviderName().isEmpty()) {
            throw new ServiceProviderValidationException("Service provider name is required");
        }

        ServiceProvider provider = ServiceProvider.builder().providerId(serviceProvider.getProviderId()).serviceProviderName(serviceProvider.getServiceProviderName()).website(serviceProvider.getWebsite()).build();

        serviceProviderRepositories.save(provider);

        return provider;
    }

    public RechargePlanes addRechargePlan(ServiceProviderRequest serviceProviderRequest) {

        RechargePlanes recharge = null;

        Optional<ServiceProvider> serviceProvider = serviceProviderRepositories.findById(serviceProviderRequest.getProviderId());
        if (serviceProvider.isPresent()) {

            Optional<RechargePlanes> rechargePlanes = rechangeRepositories.findById(serviceProviderRequest.getPlaneId());
            recharge = RechargePlanes.builder().planeId(serviceProviderRequest.getPlaneId()).activationCode(serviceProviderRequest.getActivationCode()).planAmount(serviceProviderRequest.getPlanAmount()).planDescription(serviceProviderRequest.getPlanDescription()).additionalBenefits(serviceProviderRequest.getAdditionalBenefits()).planName(serviceProviderRequest.getPlanName()).planType(serviceProviderRequest.getPlanType()).dataLimitMB(serviceProviderRequest.getDataLimitMB()).providerName(serviceProviderRequest.getProviderName()).dataUsagePolicy(serviceProviderRequest.getDataUsagePolicy()).specialNotes(serviceProviderRequest.getSpecialNotes()).validityDays(serviceProviderRequest.getValidityDays()).voiceMinutes(serviceProviderRequest.getVoiceMinutes()).isInternational(serviceProviderRequest.isInternational()).coverageArea(serviceProviderRequest.getCoverageArea()).isLimitedTimeOffer(serviceProviderRequest.isLimitedTimeOffer()).serviceProvider(serviceProvider.get()).providerId(serviceProviderRequest.getProviderId()).build();

            rechangeRepositories.save(recharge);

        } else {

            throw new ServiceProviderValidationException("Service Provider Detail is not Found..");
        }

        return recharge;
    }

    // From here users will user belows

    @Override
    public RechargeResponse rechargeNow(RechargeRequest rechargeRequest) {

        RechargeResponse rechargeResponse = new RechargeResponse();

        Optional<ServiceProvider> serviceProvider = serviceProviderRepositories.findByServiceProviderName(rechargeRequest.getServiceProviderName());
        if (serviceProvider.isPresent() && Objects.equals(serviceProvider.get().getServiceProviderName(),
                rechargeRequest.getPlanName())) {

            Optional<RechargePlanes> rechargePlanesId = rechangeRepositories.
                    findByPlanIdAndPlanNameAndPlanAmount(rechargeRequest.getPlanId(), rechargeRequest.getPlanName(),
                            String.valueOf(rechargeRequest.getPlanAmount()));


            double remainAmount = 0;
            if (rechargePlanesId.isPresent()) {

                try {

                    // Calling bank Account Information Service
                    ResponseEntity<AccountInformation> response = restTemplate.getForEntity(URL_FOR_ACCOUNT_SERVICE + rechargeRequest.getAccountNumber() + "/" + rechargeRequest.getIfscCode() + "/" + rechargeRequest.getPassword(), AccountInformation.class);
                    AccountInformation accountInformation = response.getBody();


                    if (accountInformation != null) {

                        double accountBalance = accountInformation.getAccountBalance();
                        double rechargePack = rechargeRequest.getPlanAmount();
                        remainAmount = accountBalance - rechargePack;

                        UpdateAccountBalanceRequest updateRequest = new UpdateAccountBalanceRequest();
                        updateRequest.setAccountNumber(rechargeRequest.getAccountNumber());
                        updateRequest.setAccountBalance(remainAmount);

                        restTemplate.put(URL_FOR_ACCOUNT_UPDATE_SERVICE, updateRequest);
                    }

                RechargePlanes rechargePlanes = rechargePlanesId.get();

                rechargeResponse.setPlanName(rechargePlanes.getPlanName());
                rechargeResponse.setPlanAmount(rechargePlanes.getPlanAmount());
                rechargeResponse.setValidityDays(rechargePlanes.getValidityDays());
                rechargeResponse.setDataLimitMB(rechargePlanes.getDataLimitMB());
                rechargeResponse.setVoiceMinutes(rechargePlanes.getVoiceMinutes());
                rechargeResponse.setPlanDescription(rechargePlanes.getPlanDescription());
                rechargeResponse.setProviderName(rechargePlanes.getProviderName());
                rechargeResponse.setInternational(rechargePlanes.isInternational());
                rechargeResponse.setLimitedTimeOffer(rechargePlanes.isLimitedTimeOffer());
                rechargeResponse.setPlanType(rechargePlanes.getPlanType());
                rechargeResponse.setDataUsagePolicy(rechargePlanes.getDataUsagePolicy());
                rechargeResponse.setActivationCode(rechargePlanes.getActivationCode());
                rechargeResponse.setCoverageArea(rechargePlanes.getCoverageArea());
                rechargeResponse.setSpecialNotes(rechargePlanes.getSpecialNotes());
                rechargeResponse.setAdditionalBenefits(rechargePlanes.getAdditionalBenefits());
                rechargeResponse.setRechargeStatus(RECHARGE_SUCCESSFUL);
                rechargeResponse.setAccountStatus(DICUCTION_IN_ACCOUNT_BALANCE);
                rechargeResponse.setRemainAccountBalance(String.valueOf(remainAmount));
                notificationsUtility.sendForRechargeDoneAndAccountBalanceDone(remainAmount);

                } catch (HttpClientErrorException e){
                    throw new ServiceIsDownException("The server is currently unavailable. Please try again later.");
                } catch (RestClientException e){
                    throw new ServiceIsDownException("Server is currently down for maintenance. Please try again later.");
                }

            } else {
                throw new RechargePlanNotFoundException("Apologies, the selected recharge pack details are not available in our database.");
            }

        } else {
            throw new RechargePlanNotFoundException("Apologies, the selected service provider details are not available in our database.");
        }

        return rechargeResponse;
    }


    @Override
    public List<RechargePlanes> getAllRechargePlansById(String providedId) {
        return rechangeRepositories.findByProviderId(providedId);
    }

    @Override
    public List<RechargePlanes> getAllRechargeAboveTheGivenAmount(ProviderRequest providerRequest) {

        List<RechargePlanes> rechargePlanes = rechangeRepositories.findByProviderId(providerRequest.getProviderId());
        List<RechargePlanes> list = new ArrayList<>();

        for (RechargePlanes plans : rechargePlanes) {
            try {
                if (plans.getPlanAmount() > providerRequest.getAmount()) {
                    list.add(plans);
                }
            } catch (RechargePacksNotFound e) {
                throw new RechargePlanNotFoundException("Recharge packs are not available for the given amount");
            }
        }

        if (list.isEmpty()) {
            throw new RechargePlanNotFoundException("No recharge plans found below the given amount");
        }
        return list;
    }

    @Override
    public List<RechargePlanes> getAllRechargeBelowTheGivenAmount(ProviderRequest providerRequest, double BelowAmount) {

        List<RechargePlanes> rechargePlanes = rechangeRepositories.findByProviderId(providerRequest.getProviderId());
        List<RechargePlanes> list = new ArrayList<>();

        for (RechargePlanes plans : rechargePlanes) {
            try {
                if (plans.getPlanAmount() < BelowAmount) {
                    list.add(plans);
                }

            } catch (RechargePacksNotFound e) {
                throw new RechargePlanNotFoundException("Recharge packs are not available for the given amount");
            }
        }
        if (list.isEmpty()) {
            throw new RechargePlanNotFoundException("No recharge plans found below the given amount");
        }
        return list;
    }

    @Override
    public ActivationInfo getActivationInfoByRechargePacks(String planId) {

        ActivationInfo activationInfo = new ActivationInfo();
        Optional<RechargePlanes> rechargePlanes = rechangeRepositories.findByPlanId(planId);

        if(rechargePlanes.isPresent()){

            activationInfo.setActivationCode(rechargePlanes.get().getActivationCode());
            activationInfo.setMessage("To Active Your Plan, Use Above Code");
        }
        else{

            throw new RechargePlanNotFoundException("Pack Not Found..");
        }

        return activationInfo;
    }

}



