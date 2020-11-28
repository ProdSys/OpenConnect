/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.interoperation.service;

import static org.apache.fineract.interoperation.util.InteropUtil.DEFAULT_LOCALE;
import static org.apache.fineract.interoperation.util.InteropUtil.DEFAULT_ROUTING_CODE;
import static org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail.instance;
import static org.apache.fineract.portfolio.savings.SavingsAccountTransactionType.AMOUNT_HOLD;
import static org.apache.fineract.portfolio.savings.SavingsAccountTransactionType.DEPOSIT;
import static org.apache.fineract.portfolio.savings.SavingsAccountTransactionType.WITHDRAWAL;
import static org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction.releaseAmount;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.RoutingDataSource;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.interoperation.data.InteropAccountData;
import org.apache.fineract.interoperation.data.InteropIdentifierAccountResponseData;
import org.apache.fineract.interoperation.data.InteropIdentifierRequestData;
import org.apache.fineract.interoperation.data.InteropIdentifiersResponseData;
import org.apache.fineract.interoperation.data.InteropKycData;
import org.apache.fineract.interoperation.data.InteropKycResponseData;
import org.apache.fineract.interoperation.data.InteropQuoteRequestData;
import org.apache.fineract.interoperation.data.InteropQuoteResponseData;
import org.apache.fineract.interoperation.data.InteropRequestData;
import org.apache.fineract.interoperation.data.InteropTransactionRequestData;
import org.apache.fineract.interoperation.data.InteropTransactionRequestResponseData;
import org.apache.fineract.interoperation.data.InteropTransactionsData;
import org.apache.fineract.interoperation.data.InteropTransferRequestData;
import org.apache.fineract.interoperation.data.InteropTransferResponseData;
import org.apache.fineract.interoperation.data.MoneyData;
import org.apache.fineract.interoperation.domain.InteropActionState;
import org.apache.fineract.interoperation.domain.InteropIdentifier;
import org.apache.fineract.interoperation.domain.InteropIdentifierRepository;
import org.apache.fineract.interoperation.domain.InteropIdentifierType;
import org.apache.fineract.interoperation.serialization.InteropDataValidator;
import org.apache.fineract.interoperation.util.MathUtil;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepository;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.data.LoanAccountData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepository;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDomainService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InteropServiceImpl implements InteropService {

    private static final Logger LOG = LoggerFactory.getLogger(InteropServiceImpl.class);

    private final PlatformSecurityContext securityContext;
    private final InteropDataValidator dataValidator;

    private final SavingsAccountRepository savingsAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final ApplicationCurrencyRepository currencyRepository;
    private final NoteRepository noteRepository;
    private final PaymentTypeRepository paymentTypeRepository;
    private final InteropIdentifierRepository identifierRepository;
    private final LoanRepository loanRepository;

    private final SavingsHelper savingsHelper;
    private final SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper;

    private final SavingsAccountDomainService savingsAccountService;

    private final JdbcTemplate jdbcTemplate;

    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;

    private final DefaultToApiJsonSerializer<LoanAccountData> toApiJsonSerializer;

    @Autowired
    public InteropServiceImpl(PlatformSecurityContext securityContext, InteropDataValidator interopDataValidator,
            SavingsAccountRepository savingsAccountRepository, SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            ApplicationCurrencyRepository applicationCurrencyRepository, NoteRepository noteRepository,
            PaymentTypeRepository paymentTypeRepository, InteropIdentifierRepository identifierRepository, LoanRepository loanRepository,
            SavingsHelper savingsHelper, SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper,
            SavingsAccountDomainService savingsAccountService, final RoutingDataSource dataSource,
            final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService,
            final DefaultToApiJsonSerializer<LoanAccountData> toApiJsonSerializer) {
        this.securityContext = securityContext;
        this.dataValidator = interopDataValidator;
        this.savingsAccountRepository = savingsAccountRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
        this.currencyRepository = applicationCurrencyRepository;
        this.noteRepository = noteRepository;
        this.paymentTypeRepository = paymentTypeRepository;
        this.identifierRepository = identifierRepository;
        this.loanRepository = loanRepository;
        this.savingsHelper = savingsHelper;
        this.savingsAccountTransactionSummaryWrapper = savingsAccountTransactionSummaryWrapper;
        this.savingsAccountService = savingsAccountService;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.commandsSourceWritePlatformService = commandsSourceWritePlatformService;
        this.toApiJsonSerializer = toApiJsonSerializer;
    }

    private static final class KycMapper implements RowMapper<InteropKycData> {

        public String schema() {
            return " country.code_value as nationality, c.`date_of_birth` as dateOfBirth, c.`mobile_no` as contactPhone, gender.code_value as gender, c.`email_address` as email, "
                    + "kyc.code_value as idType, ci.`document_key` as idNo, ci.`description` as description, "
                    + "country.code_value as country, a.`address_line_1`, a.`address_line_2`, "
                    + "a.`city`, state.code_value as stateProvince, a.`postal_code` as postalCode, c.`firstname` as firstName, c.`middlename` as middleName,"
                    + "c.`lastname` as lastName, c.`display_name` as displayName" + " from " + "m_client c "
                    + "left join m_client_address ca on c.id=ca.client_id " + "left join m_address a on a.id = ca.address_id "
                    + "inner join m_code_value gender on gender.id=c.`gender_cv_id` "
                    + "left join m_code_value country on country.id=a.`country_id` "
                    + "left join m_code_value state on state.id = a.`state_province_id` "
                    + "left join m_client_identifier ci on c.id=ci.`client_id` "
                    + "left join m_code_value kyc on kyc.id = ci.`document_type_id` ";
        }

        @Override
        public InteropKycData mapRow(final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {

            final String nationality = rs.getString("nationality");
            final String dateOfBirth = rs.getString("dateOfBirth");
            final String contactPhone = rs.getString("contactPhone");
            final String gender = rs.getString("gender");
            final String email = rs.getString("email");
            final String idType = rs.getString("idType");
            final String idNo = rs.getString("idNo");
            final String description = rs.getString("description");
            final String country = rs.getString("country");
            final String addressLine1 = rs.getString("address_line_1");
            final String addressLine2 = rs.getString("address_line_2");
            final String city = rs.getString("city");
            final String stateProvince = rs.getString("stateProvince");
            final String postalCode = rs.getString("postalCode");
            final String firstName = rs.getString("firstName");
            final String middleName = rs.getString("middleName");
            final String lastName = rs.getString("lastName");
            final String displayName = rs.getString("displayName");

            return InteropKycData.instance(nationality, dateOfBirth, contactPhone, gender, email, idType, idNo, description, country,
                    addressLine1, addressLine2, city, stateProvince, postalCode, firstName, middleName, lastName, displayName);
        }
    }

    @NotNull
    @Override
    @Transactional
    public InteropAccountData getAccountDetails(@NotNull String accountId) {
        return InteropAccountData.build(validateAndGetSavingAccount(accountId));
    }

    @NotNull
    @Override
    @Transactional
    public InteropTransactionsData getAccountTransactions(@NotNull String accountId, boolean debit, boolean credit,
            java.time.LocalDateTime transactionsFrom, java.time.LocalDateTime transactionsTo) {
        SavingsAccount savingsAccount = validateAndGetSavingAccount(accountId);
        ZoneId zoneId = ZoneId.of(ThreadLocalContextUtil.getTenant().getTimezoneId());
        Predicate<SavingsAccountTransaction> transFilter = t -> {
            SavingsAccountTransactionType transactionType = SavingsAccountTransactionType.fromInt(t.getTypeOf());
            if (debit != transactionType.isDebit() && credit != transactionType.isCredit()) {
                return false;
            }

            if (transactionsFrom == null && transactionsTo == null) {
                return true;
            }

            java.time.LocalDateTime transactionDate = t.getTransactionLocalDate().atStartOfDay(DateUtils.getDateTimeZoneOfTenant())
                    .toLocalDateTime();
            return (transactionsTo == null || transactionsTo.compareTo(transactionDate) > 0) && (transactionsFrom == null
                    || transactionsFrom.compareTo(transactionDate.withHour(23).withMinute(59).withSecond(59)) <= 0);
        };
        return InteropTransactionsData.build(savingsAccount, transFilter);
    }

    @NotNull
    @Override
    @Transactional
    public InteropIdentifiersResponseData getAccountIdentifiers(@NotNull String accountId) {
        SavingsAccount savingsAccount = validateAndGetSavingAccount(accountId);
        return InteropIdentifiersResponseData.build(savingsAccount);
    }

    @NotNull
    @Transactional
    @Override
    public InteropIdentifierAccountResponseData getAccountByIdentifier(@NotNull InteropIdentifierType idType, @NotNull String idValue,
            String subIdOrType) {
        InteropIdentifier identifier = findIdentifier(idType, idValue, subIdOrType);
        if (identifier == null) {
            throw new UnsupportedOperationException(
                    "Account not found for identifier " + idType + "/" + idValue + (subIdOrType == null ? "" : ("/" + subIdOrType)));
        }

        return InteropIdentifierAccountResponseData.build(identifier.getAccount().getExternalId());
    }

    @NotNull
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public InteropIdentifierAccountResponseData registerAccountIdentifier(@NotNull InteropIdentifierType idType, @NotNull String idValue,
            String subIdOrType, @NotNull JsonCommand command) {
        InteropIdentifierRequestData request = dataValidator.validateAndParseCreateIdentifier(idType, idValue, subIdOrType, command);
        // TODO: error handling
        SavingsAccount savingsAccount = validateAndGetSavingAccount(request.getAccountId());

        AppUser createdBy = getLoginUser();

        InteropIdentifier identifier = new InteropIdentifier(savingsAccount, request.getIdType(), request.getIdValue(),
                request.getSubIdOrType(), createdBy.getUsername(), DateUtils.getDateOfTenant());

        identifierRepository.save(identifier);

        return InteropIdentifierAccountResponseData.build(savingsAccount.getExternalId());
    }

    @NotNull
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public InteropIdentifierAccountResponseData deleteAccountIdentifier(@NotNull InteropIdentifierType idType, @NotNull String idValue,
            String subIdOrType) {
        InteropIdentifier identifier = findIdentifier(idType, idValue, subIdOrType);
        if (identifier == null) {
            throw new UnsupportedOperationException(
                    "Account not found for identifier " + idType + "/" + idValue + (subIdOrType == null ? "" : ("/" + subIdOrType)));
        }

        String accountId = identifier.getAccount().getExternalId();

        identifierRepository.delete(identifier);

        return InteropIdentifierAccountResponseData.build(accountId);
    }

    @Override
    public InteropTransactionRequestResponseData getTransactionRequest(@NotNull String transactionCode, @NotNull String requestCode) {
        // always REJECTED until request info is stored
        return InteropTransactionRequestResponseData.build(transactionCode, InteropActionState.REJECTED, requestCode);
    }

    @Override
    @NotNull
    @Transactional(propagation = Propagation.MANDATORY)
    public InteropTransactionRequestResponseData createTransactionRequest(@NotNull JsonCommand command) {
        // only when Payee request transaction from Payer, so here role must be
        // always Payer
        InteropTransactionRequestData request = dataValidator.validateAndParseCreateRequest(command);

        // TODO: error handling
        SavingsAccount savingsAccount = validateAndGetSavingAccount(request);

        return InteropTransactionRequestResponseData.build(command.commandId(), request.getTransactionCode(), InteropActionState.ACCEPTED,
                request.getExpiration(), request.getExtensionList(), request.getRequestCode());
    }

    @Override
    public InteropQuoteResponseData getQuote(@NotNull String transactionCode, @NotNull String quoteCode) {
        return null;
    }

    @Override
    @NotNull
    @Transactional(propagation = Propagation.MANDATORY)
    public InteropQuoteResponseData createQuote(@NotNull JsonCommand command) {
        InteropQuoteRequestData request = dataValidator.validateAndParseCreateQuote(command);

        SavingsAccount savingsAccount = validateAndGetSavingAccount(request);
        SavingsAccountTransactionType transactionType = request.getTransactionRole().getTransactionType();

        final BigDecimal fee;
        if (transactionType.isDebit()) {
            fee = savingsAccount.calculateWithdrawalFee(request.getAmount().getAmount());
            if (MathUtil.isLessThan(savingsAccount.getWithdrawableBalance(), request.getAmount().getAmount().add(fee))) {
                throw new UnsupportedOperationException("Not enough balance amount for requested amount plus fees!");
            }
        } else {
            fee = BigDecimal.ZERO;
        }

        return InteropQuoteResponseData.build(command.commandId(), request.getTransactionCode(), InteropActionState.ACCEPTED,
                request.getExpiration(), request.getExtensionList(), request.getQuoteCode(),
                MoneyData.build(fee, savingsAccount.getCurrency().getCode()), null);
    }

    @Override
    public InteropTransferResponseData getTransfer(@NotNull String transactionCode, @NotNull String transferCode) {
        return null;
    }

    @Override
    @NotNull
    @Transactional(propagation = Propagation.MANDATORY)
    public InteropTransferResponseData prepareTransfer(@NotNull JsonCommand command) {
        InteropTransferRequestData request = dataValidator.validateAndParseTransferRequest(command);
        String transferCode = request.getTransferCode();
        LocalDateTime transactionDate = DateUtils.getLocalDateTimeOfTenant();

        // TODO validate request fee/comission and account quote amount
        // matching, at CREATE it is debited anyway

        SavingsAccountTransactionType transactionType = request.getTransactionRole().getTransactionType();
        if (transactionType.isDebit()) {
            SavingsAccount savingsAccount = validateAndGetSavingAccount(request);
            BigDecimal total = calculateTotalTransferAmount(request, savingsAccount);

            if (MathUtil.isLessThan(savingsAccount.getWithdrawableBalance(), total)) {
                throw new UnsupportedOperationException("Not enough balance amount for requested amount plus fees!");
            }
            if (findTransaction(savingsAccount, transferCode, AMOUNT_HOLD.getValue()) != null) {
                throw new UnsupportedOperationException("Transfer amount was already put on hold " + transferCode);
            }

            PaymentDetail paymentDetail = instance(findPaymentType(), savingsAccount.getExternalId(), null, getRoutingCode(), transferCode,
                    null);
            SavingsAccountTransaction holdTransaction = SavingsAccountTransaction.holdAmount(savingsAccount, savingsAccount.office(),
                    paymentDetail, transactionDate.toLocalDate(), Money.of(savingsAccount.getCurrency(), total), new Date(),
                    getLoginUser());
            MonetaryCurrency accountCurrency = savingsAccount.getCurrency().copy();
            holdTransaction.updateRunningBalance(
                    Money.of(accountCurrency, savingsAccount.getWithdrawableBalance().subtract(holdTransaction.getAmount())));
            holdTransaction.updateCumulativeBalanceAndDates(accountCurrency, transactionDate.toLocalDate());

            savingsAccount.holdAmount(total);
            savingsAccount.addTransaction(holdTransaction);

            savingsAccountRepository.save(savingsAccount);
        }

        return InteropTransferResponseData.build(command.commandId(), request.getTransactionCode(), InteropActionState.ACCEPTED,
                request.getExpiration(), request.getExtensionList(), transferCode, transactionDate);
    }

    @Override
    @NotNull
    @Transactional(propagation = Propagation.MANDATORY)
    public InteropTransferResponseData commitTransfer(@NotNull JsonCommand command) {
        InteropTransferRequestData request = dataValidator.validateAndParseTransferRequest(command);
        boolean isDebit = request.getTransactionRole().getTransactionType().isDebit();
        SavingsAccount savingsAccount = validateAndGetSavingAccount(request);
        String transferCode = request.getTransferCode();

        if (findTransaction(savingsAccount, transferCode, (isDebit ? WITHDRAWAL : DEPOSIT).getValue()) != null) {
            throw new UnsupportedOperationException("Transfer was already committed with code: " + transferCode);
        }

        LocalDateTime transactionDateTime = DateUtils.getLocalDateTimeOfTenant();
        LocalDate transactionDate = transactionDateTime.toLocalDate();
        DateTimeFormatter fmt = getDateTimeFormatter(command);
        SavingsAccountTransaction transaction;

        if (isDebit) {
            SavingsAccountTransaction holdTransaction = findTransaction(savingsAccount, transferCode, AMOUNT_HOLD.getValue());
            if (holdTransaction == null) {
                throw new UnsupportedOperationException("Missing onhold transaction for transfer: " + transferCode);
            }

            BigDecimal totalTransferAmount = calculateTotalTransferAmount(request, savingsAccount);
            if (holdTransaction.getAmount().compareTo(totalTransferAmount) != 0) {
                throw new UnsupportedOperationException("Transfer request amount plus fees does not match with onhold amount!");
            }

            if (MathUtil.isLessThan(savingsAccount.getWithdrawableBalance().add(holdTransaction.getAmount()), totalTransferAmount)) {
                throw new UnsupportedOperationException("Not enough balance amount!");
            }

            if (holdTransaction.getReleaseIdOfHoldAmountTransaction() == null) {
                SavingsAccountTransaction releaseTransaction = savingsAccountTransactionRepository
                        .saveAndFlush(releaseAmount(holdTransaction, transactionDate, new Date(), getLoginUser()));
                holdTransaction.updateReleaseId(releaseTransaction.getId());
                savingsAccount.releaseOnHoldAmount(holdTransaction.getAmount());
                savingsAccount.addTransaction(releaseTransaction);

                savingsAccountRepository.save(savingsAccount);
            }

            SavingsTransactionBooleanValues transactionValues = new SavingsTransactionBooleanValues(false, true, true, false, false);
            transaction = savingsAccountService.handleWithdrawal(savingsAccount, fmt, transactionDate, request.getAmount().getAmount(),
                    instance(findPaymentType(), savingsAccount.getExternalId(), null, getRoutingCode(), transferCode, null),
                    transactionValues);
        } else {
            transaction = savingsAccountService.handleDeposit(savingsAccount, fmt, transactionDate, request.getAmount().getAmount(),
                    instance(findPaymentType(), savingsAccount.getExternalId(), null, getRoutingCode(), transferCode, null), false, true);
        }

        String note = request.getNote();
        if (!StringUtils.isBlank(note)) {
            noteRepository.save(Note.savingsTransactionNote(savingsAccount, transaction, note));
        }

        return InteropTransferResponseData.build(command.commandId(), request.getTransactionCode(), InteropActionState.ACCEPTED,
                request.getExpiration(), request.getExtensionList(), request.getTransferCode(), transactionDateTime);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public @NotNull InteropTransferResponseData releaseTransfer(@NotNull JsonCommand command) {
        InteropTransferRequestData request = dataValidator.validateAndParseTransferRequest(command);
        SavingsAccount savingsAccount = validateAndGetSavingAccount(request);

        LocalDateTime transactionDateTime = DateUtils.getLocalDateTimeOfTenant();
        SavingsAccountTransaction holdTransaction = findTransaction(savingsAccount, request.getTransferCode(), AMOUNT_HOLD.getValue());

        if (holdTransaction != null && holdTransaction.getReleaseIdOfHoldAmountTransaction() == null) {
            SavingsAccountTransaction releaseTransaction = releaseAmount(holdTransaction, transactionDateTime.toLocalDate(), new Date(),
                    getLoginUser());
            MonetaryCurrency accountCurrency = savingsAccount.getCurrency().copy();
            releaseTransaction.updateRunningBalance(
                    Money.of(accountCurrency, savingsAccount.getWithdrawableBalance().add(holdTransaction.getAmount())));
            releaseTransaction.updateCumulativeBalanceAndDates(accountCurrency, transactionDateTime.toLocalDate());
            releaseTransaction = savingsAccountTransactionRepository.saveAndFlush(releaseTransaction);
            holdTransaction.updateReleaseId(releaseTransaction.getId());

            savingsAccount.releaseOnHoldAmount(holdTransaction.getAmount());
            savingsAccount.addTransaction(releaseTransaction);

            savingsAccountRepository.save(savingsAccount);
        } else {
            throw new UnsupportedOperationException("No active onhold transaction exists with transferCode " + request.getTransferCode());
        }

        return InteropTransferResponseData.build(command.commandId(), request.getTransactionCode(), InteropActionState.ACCEPTED,
                request.getExpiration(), request.getExtensionList(), request.getTransferCode(), transactionDateTime);
    }

    @Override
    public @NotNull InteropKycResponseData getKyc(@NotNull @NotNull String accountId) {

        SavingsAccount savingsAccount = validateAndGetSavingAccount(accountId);
        Long client_id = savingsAccount.getClient().getId();

        try {
            final InteropServiceImpl.KycMapper rm = new InteropServiceImpl.KycMapper();
            final String sql = "select " + rm.schema() + " where c.id = ?";

            final InteropKycData accountKyc = this.jdbcTemplate.queryForObject(sql, rm, new Object[] { client_id });

            return InteropKycResponseData.build(accountKyc);
        } catch (final EmptyResultDataAccessException e) {
            throw new UnsupportedOperationException("Error in retrieving KYC information: " + e);
        }
    }

    @Override
    public @NotNull String disburseLoan(@NotNull String accountId, String apiRequestBodyAsJson) {
        Loan loan = validateAndGetLoan(accountId);
        Long loanId = loan.getId();

        LocalDateTime disbursedOnDate = DateUtils.getLocalDateTimeOfTenant();

        final CommandWrapperBuilder builder = new CommandWrapperBuilder().withJson(apiRequestBodyAsJson);

        final CommandWrapper commandRequest = builder.disburseLoanApplication(loanId).build();
        CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    private SavingsAccount validateAndGetSavingAccount(String accountId) {
        SavingsAccount savingsAccount = savingsAccountRepository.findByExternalId(accountId);
        if (savingsAccount == null) {
            throw new SavingsAccountNotFoundException(accountId);
        }
        return savingsAccount;
    }

    private Loan validateAndGetLoan(String accountId) {
        Loan loan = loanRepository.findNonClosedLoanByAccountNumber(accountId);
        if (loan == null) {
            throw new UnsupportedOperationException("Loan not found for the given account No: " + accountId);
        }
        return loan;
    }

    private SavingsAccount validateAndGetSavingAccount(@NotNull InteropRequestData request) {
        // TODO: error handling
        SavingsAccount savingsAccount = validateAndGetSavingAccount(request.getAccountId());
        savingsAccount.setHelpers(savingsAccountTransactionSummaryWrapper, savingsHelper);

        ApplicationCurrency requestCurrency = currencyRepository.findOneByCode(request.getAmount().getCurrency());
        if (!savingsAccount.getCurrency().getCode().equals(requestCurrency.getCode())) {
            throw new UnsupportedOperationException("Account and request has different currencies!");
        }

        SavingsAccountTransactionType transactionType = request.getTransactionRole().getTransactionType();
        if (!savingsAccount.isTransactionAllowed(transactionType, request.getExpirationLocalDate())) {
            throw new UnsupportedOperationException("Transaction not allowed on account!");
        }

        request.normalizeAmounts(savingsAccount.getCurrency());

        return savingsAccount;
    }

    private BigDecimal calculateTotalTransferAmount(@NotNull InteropTransferRequestData request, @NotNull SavingsAccount savingsAccount) {
        BigDecimal total = request.getAmount().getAmount();
        MoneyData requestFee = request.getFspFee();
        if (requestFee != null) {
            if (!savingsAccount.getCurrency().getCode().equals(requestFee.getCurrency())) {
                throw new UnsupportedOperationException();
            }
            // TODO: compare with calculated quote fee
            total = MathUtil.add(total, requestFee.getAmount());
        }
        MoneyData requestCommission = request.getFspCommission();
        if (requestCommission != null) {
            if (!savingsAccount.getCurrency().getCode().equals(requestCommission.getCurrency())) {
                throw new UnsupportedOperationException();
            }
            // TODO: compare with calculated quote commission
            total = MathUtil.subtractToZero(total, requestCommission.getAmount());
        }
        return total;
    }

    private DateTimeFormatter getDateTimeFormatter(@NotNull JsonCommand command) {
        Locale locale = command.extractLocale();
        if (locale == null) {
            locale = DEFAULT_LOCALE;
        }
        String dateFormat = command.dateFormat();
        if (StringUtils.isEmpty(dateFormat)) {
            dateFormat = "yyyy-MM-dd HH:mm:ss.SSS";
        }

        return DateTimeFormatter.ofPattern(dateFormat).withLocale(locale);
    }

    PaymentType findPaymentType() {
        List<PaymentType> paymentTypes = paymentTypeRepository.findAll();
        for (PaymentType paymentType : paymentTypes) {
            if (!paymentType.isCashPayment()) {
                return paymentType;
            }
            // TODO: for now first not cash is retured:
            // 1. must be added as initial setup,
            // 2. if more than one non-cashe type added then update this code
        }
        return null;
    }

    private SavingsAccountTransaction findTransaction(SavingsAccount savingsAccount, String transactionCode, Integer transactionTypeValue) {
        return savingsAccount.getTransactions().stream().filter(t -> transactionTypeValue.equals(t.getTypeOf())).filter(t -> {
            PaymentDetail detail = t.getPaymentDetail();
            return detail != null && getRoutingCode().equals(detail.getRoutingCode()) && transactionCode.equals(detail.getReceiptNumber());
        }).findFirst().orElse(null);
    }

    public InteropIdentifier findIdentifier(@NotNull InteropIdentifierType idType, @NotNull String idValue, String subIdOrType) {
        return identifierRepository.findOneByTypeAndValueAndSubType(idType, idValue, subIdOrType);
    }

    private AppUser getLoginUser() {
        return securityContext.getAuthenticatedUserIfPresent();
    }

    @NotNull
    String getRoutingCode() {
        return DEFAULT_ROUTING_CODE;
    }
}