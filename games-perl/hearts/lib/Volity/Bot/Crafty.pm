package Volity::Bot::Hearts::Crafty;

# This is a bot that is (heavily) inspired by the algorithm used in the gnome
# hearts program at http://www.jejik.com/hearts.  It should be a good
# intermediate type bot, and most likely the default one returned when the
# "Request a bot" button is pressed.

use warnings;
use strict;

use base qw(Volity::Bot::Hearts::Base);

our $VERSION = "1.0";

__PACKAGE__->name("Crafty");
__PACKAGE__->algorithm("http://games.staticcling.org:8088/hearts/bot/crafty");
__PACKAGE__->description("A somewhat intelligent automated player");

# suit_indices holds a hash of hashes with the indexes into the cards array of
#   the hand field of the Base object.  They provide easy access to cards of a
#   given suit, and allows us us to count how many cards are in a given suit.
#   See the initialize_suit_indices function for a data definition.
# trick_cards keeps track of what cards have been played in the current trick
# unsafe_suits is a hash of suits that no one else is holding anymore
use fields qw( suit_indices trick_cards unsafe_suits );

sub initialize {
    my $self = shift;

    # an empty list to start with.
    $self->trick_cards([]);

    # all suits are safe too
    $self->unsafe_suits({});

    return $self->SUPER::initialize(@_);
}

####################  RPC Handlers ####################

# In almost every case, we want to call SUPER::rpc_handler first off, then do
# any extra stuff we need to do.

sub game_rpc_receive_hand {
    my $self = shift;
    my ($cards) = @_;

    # exhibit all of the regular behaviour
    $self->SUPER::game_rpc_receive_hand($cards);

    return unless $self->am_seated;

    # set up the indexes for finding the suits easily
    $self->calculate_suit_indices();

    # clear the current trick out (can't use the accessor, it behaves like a
    # scalar & other weirdness)
    splice(@{$self->{trick_cards}});

    # all suits are safe again
    $self->unsafe_suits({});

    if ($self->logger->is_debug()) {
        # log the start and end cards of every suit
        my @cards = @{$self->hand->cards};
        my %indices = %{$self->suit_indices};
        my $log_str = " ";

        foreach (qw(C D H S)) {
            unless ($indices{$_}->{start} == -1) {
                $log_str .= "(" . $cards[$indices{$_}->{start}]->truename . 
                    ", " .  $cards[$indices{$_}->{end}]->truename . ") " 
            } else {
                $log_str .= "(- -) "; # empty suit
            }
        }
        $self->logger->debug($self->log_prefix . $log_str);
    }

    return;
}

sub game_rpc_seat_played_card {
    my $self = shift;
    my ($seat_id, $card) = @_;

    # first off, do all of the regular things that every bot has to do
    $self->SUPER::game_rpc_seat_played_card($seat_id, $card);

    return unless $self->am_seated;

    # recalc the suit indices so everything's nice & tidy if this was our card
    if ($self->am_seated and $seat_id eq $self->seat_id) {
        $self->calculate_suit_indices();
    }

    # keep track of which cards have been played
    my $index = $self->master_hand->index($card);
    push(@{$self->{trick_cards}}, $self->master_hand->cards->[$index]);

    return;
}

sub game_rpc_seat_won_trick {
    my $self = shift;

    # preserve this, it gets cleared in the next statment
    my $led_suit = $self->led_suit;
    $self->SUPER::game_rpc_seat_won_trick(@_);

    return unless $self->am_seated;

    # figure out which suits are completely unsafe -- as in there are no
    # remaining cards, so leading that suit would just win us the trick by
    # default
    my @insuit = grep($_->suit eq $led_suit, @{$self->{trick_cards}});
    if (scalar(@insuit) == 1) {
        $self->unsafe_suits->{$insuit[0]->suit} = 1;
    }

    # clear the current trick out
    splice(@{$self->{trick_cards}});

    return;
}

sub game_rpc_pass_accepted {
    my $self = shift;
    my ($cards) = @_;

    # first off, do all of the regular things that every bot has to do
    $self->SUPER::game_rpc_pass_accepted($cards);

    return unless $self->am_seated;

    # recalculate the indexes for finding the suits easily
    $self->calculate_suit_indices();

    return;
}

sub game_rpc_receive_pass {
    my $self = shift;
    my ($cards) = @_;

    # first off, do all of the regular things that every bot has to do
    $self->SUPER::game_rpc_receive_pass($cards);

    return unless $self->am_seated;

    # recalculate the indexes for finding the suits easily
    $self->calculate_suit_indices();

    return;
}

#################### Methods Implementing Play Logic ####################

# pass_cards: does the actual work of selecting the cards to pass, and returns
# a ref to an array containing the names of the cards.
sub pass_cards {
    my $self = shift;
    my ($dir, $count) = @_;

    my $hand = $self->hand;
    my @cards = @{$self->hand->cards};
    # holds the cards to be passed
    my $pass = Games::Cards::Hand->new($self->game, "pass");

    # try to pass all of the high spades (inefficient code, but easy)
    $hand->give_a_card($pass, $hand->index("AS")) if defined $hand->index("AS");
    $hand->give_a_card($pass, $hand->index("KS")) if defined $hand->index("KS");
    $hand->give_a_card($pass, $hand->index("QS")) if defined $hand->index("QS");
    $self->calculate_suit_indices();

    while ($pass->size < 3) {
        # if there's only one club in our hand, pass it 
        if ($self->count_cards_in_suit("C") == 1) {
            $hand->give_a_card($pass, $self->suit_indices->{"C"}->{"start"});
            $self->calculate_suit_indices();
            next;
        }
        # if there's only one diamond in our hand, pass it
        if ($self->count_cards_in_suit("D") == 1) {
            $hand->give_a_card($pass, $self->suit_indices->{"D"}->{"start"});
            $self->calculate_suit_indices();
            next;
        }
        # if there's only one heart in our hand, pass it
        if ($self->count_cards_in_suit("H") == 1) {
            $hand->give_a_card($pass, $self->suit_indices->{"H"}->{"start"});
            $self->calculate_suit_indices();
            next;
        }

        # give a high heart
        my $index = $self->suit_indices->{"H"}->{"end"};
        if ($self->count_cards_in_suit("H") > 0 and $cards[$index]->value >= 10) {
            $hand->give_a_card($pass, $index);
            $self->calculate_suit_indices();
            next;
        } else { # else, just give a high card
            $hand->sort_by_value();
            $hand->give_a_card($pass, -1); # the highest card
            $hand->sort_by_suit_and_value();
            $self->calculate_suit_indices();
            next;
        }
    }

    # make the list of card names to pass
    my @chosen_names = map { $_->name . $_->suit } @{$pass->cards};
    # return the cards to our hand, in case the server rejects them somehow
    #  they'll be removed from our hand for real when the "pass_accepted" RPC
    #  comes in
    for (my $i = $pass->size - 1; $i >= 0; $i--) {
        $pass->give_a_card($hand, $i); 
    }

    $self->logger->debug($self->log_prefix . " passing cards " . join(', ', @chosen_names));
    return \@chosen_names;
}

# take_turn: choose a card to play, return its name
sub take_turn {
    my $self = shift;
    my $chosen_card = 0;
    my $action = "";

    # check for a bizzarely empty hand, and spend some time waiting for one to
    # be delivered to us -- this could be cause by timing issues where
    # (somehow) we've gotten the notification to play before we've gotten the
    # receive_hand RPC
    if ($self->hand->size == 0) {
        $self->logger->error($self->log_prefix . " empty hand in take_turn");
        return;
    }

    my $cards_played_count = scalar @{$self->{trick_cards}};
    my $queen_index = $self->hand->index("QS");

    # if we're leading, just pick the lowest card we have, unless it's a
    # heart and they haven't been broken.
    if ($cards_played_count == 0) {
        $self->hand->sort_by_value();
        $chosen_card = $self->hand->cards->[0];

        # If the chosen card's suit is just going to win us the trick because
        # no one has any of that suit anymore, find the next lowest card of a
        # different suit
        my $index = 0;
        my @hand = @{$self->hand->cards};
        if ($self->unsafe_suits->{$chosen_card->suit}) {
            for (; $index < scalar(@hand); $index++) {
                my $card = $hand[$index];

                if (not $self->unsafe_suits->{$card->suit}) {
                    $chosen_card = $card;
                    last;
                }
            }
        }

        # Find the lowest non-heart we have, if all we have is hearts, the
        # above choice will be used.  This may result in a return to the suit
        # avoided above, but that should be fairly rare, as hearts will
        # usually be broken long before one person holds the last of a given
        # suit.  Start looking at the array location of the present chosen_card
        if (not $self->hearts_broken and $chosen_card->suit eq 'H') {
            for (; $index < scalar(@hand); $index++) {
                my $card = $hand[$index];

                if ($card->suit ne 'H') {
                    $chosen_card = $card;
                    last;
                }
            }
        }

        # track the led suit here too
        $self->led_suit($chosen_card->suit);

        # put the sorting back the way it should be
        $self->hand->sort_by_suit_and_value();

        $action = "Leading ";
    } else {
        $action = "Playing ";

        # determine whether we can follow suit, arrange the in-suit cards
        # that have been played in ascending order
        my $can_follow_suit = $self->count_cards_in_suit($self->led_suit);
        my @trick = sort cmp_card_value grep($_->suit eq $self->led_suit, @{$self->{trick_cards}});
        my @hand = @{$self->hand->cards};

        if ($can_follow_suit) {
            # these are the in-suit cards in our hand
            my @valid_cards = @hand[
                $self->suit_indices->{$self->led_suit}->{"start"} ..
                $self->suit_indices->{$self->led_suit}->{"end"}];
            $self->logger->debug("Valid cards ($can_follow_suit): " . 
                join(", ", map($_->truename, @valid_cards)));

            if ($self->led_suit eq 'S' and defined $queen_index and 
                grep($_->value > $hand[$queen_index]->value, @trick))
            {
                # if the suit is spades, we have the queen, and someone's 
                #  played a higher card, get rid of the queen
                $chosen_card = $hand[$queen_index];
            } elsif ($cards_played_count == 3) {
                # special behaviour for the last card of the trick
                if ($self->points_in_trick()) {
                    # if there are points in the trick, play the highest card
                    # that doesn't take the trick
                    $chosen_card = find_card_below($trick[-1], @valid_cards);

                    # if we don't have any such, play our highest card, unless
                    # that's the Queen of Spades, and we have anything else
                    unless (defined $chosen_card) {
                        $chosen_card = $valid_cards[-1];
                        if ($chosen_card->truename eq "QS" and
                            $can_follow_suit > 1) 
                        {
                                $chosen_card = $valid_cards[-2];
                        }
                    }
                } else {
                    # if there aren't points in the trick, play our highest
                    # in-suit card (that's not the QS unless we can't help it)
                    $chosen_card = $valid_cards[-1];
                    if ($chosen_card->truename eq "QS" and $can_follow_suit > 1) {
                        $chosen_card = $valid_cards[-2];
                    }
                }
            } elsif (scalar @hand >= 13) {
                # if this is the first trick of a new round, no points can be
                # laid, so play our highest in-suit card (club)
                $chosen_card = $valid_cards[-1];
            } else {
                # play the highest card that doesn't take the trick
                $chosen_card = find_card_below($trick[-1], @valid_cards);

                # if we don't have any such, play our lowest card, unless
                # that's the Queen of Spades (and we have anything else)
                unless (defined $chosen_card) {
                    $chosen_card = $valid_cards[0];
                    if ($chosen_card->truename eq "QS" and $can_follow_suit > 1) {
                        $chosen_card = $valid_cards[1];
                    }
                }
            }
        } else {
            # We couldn't find an in-suit card.  Toss cards in this order:
            #   the QS, A - 10 of Hearts (in random order, 90% of the time), 
            #   our highest card (90% of the time), our second highest card
            $action = "Sloughing ";
            my $first_trick = ($self->hand->size == 13); # the first trick?
            my @hearts = @hand[
                $self->suit_indices->{"H"}->{"start"} ..
                $self->suit_indices->{"H"}->{"end"}]; # all of our hearts
            my @highhearts = grep($_->value >= 10, @hearts);

            if (not $first_trick and defined $queen_index) {
                # toss the queen
                $chosen_card = $self->hand->cards->[$queen_index];
            } elsif (not $first_trick and scalar @highhearts >= 1 
                and rand() < 0.90)
            {
                # randomly toss one of our highest hearts
                $chosen_card = $highhearts[int(rand(scalar @highhearts))];
            } else {
                # toss our highest card
                $self->hand->sort_by_value();
                if (rand() < 0.90 or scalar(@hand) < 2) {
                    $chosen_card = $self->hand->cards->[-1];
                } else {
                    $chosen_card = $self->hand->cards->[-2];
                }

                # if this is the first trick of a round, don't play points if it's
                # avoidable
                if ($self->hand->size == 13 and $self->point_card($chosen_card)) {
                    my $card_names = join(", ", map($_->name . $_->suit, @{$self->hand->cards}));
                    $self->logger->debug($self->log_prefix . " avoiding points in the first trick ($card_names)");

                    foreach (reverse(@{$self->hand->cards})) {
                        $chosen_card = $_ if not $self->point_card($_);
                    }

                    $card_names = join(", ", map($_->name . $_->suit, @{$self->hand->cards}));
                    $self->logger->debug($self->log_prefix . " after avoiding points: first trick ($card_names)");
                }

                # put the hand back like we want it
                $self->hand->sort_by_suit_and_value();
            }
        }
    }

    if (not defined $chosen_card or $chosen_card == 0) {
        my $card_names = join(", ", map($_->name . $_->suit, @{$self->hand->cards}));
        $self->logger->error($self->log_prefix . " no card chosen while $action ($card_names)");

        # for the time being, just try to play every card until the ref lets
        # one through
        $self->flailing(1);
        $chosen_card = $self->hand->cards->[0];
        $self->logger->info($self->log_prefix . " flailing, playing " . 
                $chosen_card->name . $chosen_card->suit);
        return $chosen_card->name . $chosen_card->suit;
    }

    my $card_names = join(", ", map($_->name . $_->suit, @{$self->hand->cards}));
    $self->logger->debug($self->log_prefix . " " . $action . $chosen_card->name . 
        $chosen_card->suit .  ", " . $self->led_suit . 
        " was led (hand: $card_names) ");
    return $chosen_card->name . $chosen_card->suit;
}

#################### Supporting Methods ####################

sub points_in_trick {
    my $self = shift;

    my $count = grep {$self->point_card($_)} @{$self->{trick_cards}};
    return $count;
}

sub calculate_suit_indices {
    my $self = shift;

    $self->suit_indices({ 
            C => {start => -1, end => -1}, # clubs
            D => {start => -1, end => -1}, # diamonds
            H => {start => -1, end => -1}, # hearts
            S => {start => -1, end => -1}  # spades
        });

    my $current_suit = "N";
    my @cards = @{$self->hand->cards};
    my %indices = %{$self->suit_indices};
    for (my $i = 0; $i <  scalar @cards; $i++) {
        my $card = $cards[$i];

        # transition between suits, record the end of the old suit, and the
        # start index of the new
        if ($card->suit ne $current_suit) {
            $indices{$current_suit}->{end} = $i - 1 
                unless $current_suit eq "N";
            $current_suit = $card->suit;
            $indices{$current_suit}->{start} = $i;
        }
    }

    # record the end index of the last suit (because there's no transition to
    # trigger it)
    $indices{$current_suit}->{end} = scalar(@cards) - 1;
}

sub count_cards_in_suit {
    my $self = shift;
    my ($suit) = @_;

    my %indices = %{$self->suit_indices};
    return 0 if $indices{$suit}->{start} == -1;
    return $indices{$suit}->{end} - $indices{$suit}->{start} + 1;
}

# NOT a method, standalone function
# this doesn't take into account suits, just values
sub cmp_card_value {
    return $a->value <=> $b->value;
}

# given a card, and a sorted (ascending) list of cards in the same suit, 
#  return the highest card that is lower than the given card.  Returns undef
#  if it can't find a lower card
sub find_card_below {
    my ($card, @cards) = @_;

    foreach (reverse @cards) {
        return $_ if $card->value > $_->value;   
    }

    return undef;
}

1;

# vim: ts=4 sw=4 et ai
